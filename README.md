# My Hero Craft Wiki

My Hero Craft（MHC）の英語 Wiki です。中身は MOD のソースから自動で作っています。

- `docs/` … 公開するページ一式（GitHub Pages はこのフォルダを公開します）
- `generator/WikiGen.java` … ページを作るプログラム
- `regen.bat` … MOD を更新したあとにダブルクリックすると、ページを作り直します

## MOD を更新したとき

1. `regen.bat` をダブルクリックする
2. GitHub に上げ直す（GitHub Desktop なら「Commit」→「Push」）

## 中身の出どころ

- 個性・技の名前と説明：`en_us.json`
- スキルツリー：`SkillTreeInit.java`
- クールダウン：`SkillCD.java`（式が複雑なものは「varies」と表示）
- キャラクター：各エンティティの `SetEntityStats`
- Guide ページ（操作・ポイントなど）だけは `WikiGen.java` の中に直接書いています
