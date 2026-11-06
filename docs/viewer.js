import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
const el = document.getElementById('viewer');
const parts = JSON.parse(document.getElementById('model-data').textContent);
const D = Math.PI / 180;
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
el.appendChild(renderer.domElement);
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 1000);
camera.position.set(-30, 22, -92);
const controls = new OrbitControls(camera, renderer.domElement);
controls.target.set(0, 16, 0);
controls.enablePan = false;
controls.minDistance = 20;
controls.maxDistance = 140;
controls.autoRotate = true;
controls.autoRotateSpeed = 1.2;
controls.addEventListener('start', () => { controls.autoRotate = false; });
const loader = new THREE.TextureLoader();

function rect(u0, v0, u1, v1, flipU, flipV) { return flipU ? (flipV ? [u1, v1, u0, v0] : [u1, v0, u0, v1]) : (flipV ? [u0, v1, u1, v0] : [u0, v0, u1, v1]); }

function faceRects(c) {
  const [w, h, d] = c.size;
  if (Array.isArray(c.uv) || !c.uv) {
    const [u, v] = c.uv || [0, 0];
    let east = rect(u, v + d, u + d, v + d + h), west = rect(u + d + w, v + d, u + 2 * d + w, v + d + h);
    const north = rect(u + d, v + d, u + d + w, v + d + h), south = rect(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h);
    const up = rect(u + d, v, u + d + w, v + d, true, true), down = rect(u + d + w, v, u + d + 2 * w, v + d, true, false);
    let r = [east, west, up, down, south, north];
    if (c.mirror) { r = [west, east, up, down, south, north].map(q => [q[2], q[1], q[0], q[3]]); }
    return r;
  }
  const f = n => { const o = c.uv[n]; if (!o) return null; const [u, v] = o.uv, [sw, sh] = o.uv_size || [0, 0]; return [u, v, u + sw, v + sh]; };
  const up = f('up'), down = f('down');
  return [f('east'), f('west'), up && [up[2], up[3], up[0], up[1]], down && [down[2], down[1], down[0], down[3]], f('south'), f('north')];
}

function cubeMesh(c, mat, tw, th) {
  const inf = c.inflate || 0, [w, h, d] = c.size;
  const geo = new THREE.BoxGeometry(Math.max(w + inf * 2, 0.001), Math.max(h + inf * 2, 0.001), Math.max(d + inf * 2, 0.001));
  const uv = geo.attributes.uv, rs = faceRects(c);
  const hidden = [];
  rs.forEach((r, i) => {
    if (!r) { hidden.push(i); return; }
    const [u0, v0, u1, v1] = r;
    uv.setXY(i * 4 + 0, u0 / tw, 1 - v0 / th); uv.setXY(i * 4 + 1, u1 / tw, 1 - v0 / th);
    uv.setXY(i * 4 + 2, u0 / tw, 1 - v1 / th); uv.setXY(i * 4 + 3, u1 / tw, 1 - v1 / th);
  });
  if (hidden.length) { const idx = geo.index.array; hidden.forEach(i => { for (let k = 0; k < 6; k++) idx[i * 6 + k] = 0; }); }
  return new THREE.Mesh(geo, mat);
}

function build(json, tex, hide) {
  const g = (json['minecraft:geometry'] || [])[0];
  if (!g) return null;
  const tw = g.description.texture_width, th = g.description.texture_height;
  const mat = new THREE.MeshBasicMaterial({ map: tex, alphaTest: 0.1, side: THREE.DoubleSide });
  const root = new THREE.Group(), bones = {};
  for (const b of g.bones || []) bones[b.name] = { b, grp: new THREE.Group() };
  const pv = b => { const p = b.pivot || [0, 0, 0]; return [-p[0], p[1], p[2]]; };
  for (const { b, grp } of Object.values(bones)) {
    const p = pv(b), parent = b.parent && bones[b.parent];
    const pp = parent ? pv(parent.b) : [0, 0, 0];
    grp.position.set(p[0] - pp[0], p[1] - pp[1], p[2] - pp[2]);
    const r = b.rotation || [0, 0, 0];
    grp.rotation.set(-r[0] * D, -r[1] * D, r[2] * D, 'ZYX');
    (parent ? parent.grp : root).add(grp);
    if (hide.includes(b.name)) grp.visible = false;
    for (const c of b.cubes || []) {
      const [ox, oy, oz] = c.origin, [w, h, d] = c.size;
      const center = [-(ox + w / 2), oy + h / 2, oz + d / 2];
      const mesh = cubeMesh(c, mat, tw, th);
      if (c.rotation) {
        const cp = c.pivot ? [-c.pivot[0], c.pivot[1], c.pivot[2]] : center;
        const holder = new THREE.Group();
        holder.position.set(cp[0] - p[0], cp[1] - p[1], cp[2] - p[2]);
        holder.rotation.set(-c.rotation[0] * D, -c.rotation[1] * D, c.rotation[2] * D, 'ZYX');
        mesh.position.set(center[0] - cp[0], center[1] - cp[1], center[2] - cp[2]);
        holder.add(mesh); grp.add(holder);
      } else {
        mesh.position.set(center[0] - p[0], center[1] - p[1], center[2] - p[2]);
        grp.add(mesh);
      }
    }
  }
  return root;
}

for (const p of parts) {
  try {
    const [json, tex] = await Promise.all([fetch(p.geo).then(r => r.json()), loader.loadAsync(p.tex)]);
    tex.magFilter = THREE.NearestFilter; tex.minFilter = THREE.NearestFilter; tex.colorSpace = THREE.SRGBColorSpace;
    const m = build(json, tex, p.hide || []);
    if (m) scene.add(m);
  } catch (e) { console.warn('model part failed', p, e); }
}
function resize() { const w = el.clientWidth, h = el.clientHeight; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
new ResizeObserver(resize).observe(el); resize();
renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });
