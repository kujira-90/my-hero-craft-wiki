const box = document.getElementById('q');
if (box) box.addEventListener('input', () => {
  const v = box.value.trim().toLowerCase();
  document.querySelectorAll('.item').forEach(a => { a.style.display = !v || a.dataset.s.includes(v) ? '' : 'none'; });
  document.querySelectorAll('.grid').forEach(g => {
    const any = [...g.children].some(c => c.style.display !== 'none');
    g.style.display = any ? '' : 'none';
    const h = g.previousElementSibling; if (h) h.style.display = any ? '' : 'none';
  });
});
