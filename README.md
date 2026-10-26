# Hermes Mobile

[Hermes Agent](https://github.com/NousResearch/hermes-agent) を Android から使うためのクライアントです。
Mac で動いている Hermes Desktop と同じ会話に、Tailscale 経由で参加します。Desktop で始めた会話をスマホで続けたり、作業中の Hermes からの質問にスマホで答えたりできます。

- Bot（Hermes のプロファイル）ごとの会話一覧と、新しい会話
- 返答のリアルタイム表示（Markdown、表、コード）
- Hermes からの選択肢・質問への回答（複数選択や、Desktop から始めた会話でまとめて来る質問にも対応）、コマンド実行の承認
- `MEDIA:` で渡されたファイルのプレビュー（HTML・画像・テキスト）
- Tailscale のアカウントでサインイン（パスワードなし）
- ライト／ダークテーマ、折りたたみスマホの 2 画面表示（Galaxy Z Fold で確認）、タブレットでの会話の並列表示（最大 3 列）

## 必要なもの

- **Mac**：Hermes Desktop（Hermes Agent が `~/.hermes` に入っている状態）と、Tailscale の Mac アプリ（サインイン済み・MagicDNS 有効）
- **スマホ**：Android 12 以上と、Tailscale のアプリ（Mac と同じ tailnet に参加済み）

通信は tailnet の中だけで完結し、インターネットには公開しません。

## セットアップ

### 1. Mac

Mac の Terminal で次を実行します。

```sh
curl -fsSL https://github.com/k02miu/hermes-client/releases/latest/download/hermes-mobile-mac.tar.gz | tar xz
./hermes-mobile-mac/install.sh
```

- 初めて入れたときは、終わったあと Hermes Desktop を一度再起動してください。
- 最後に、スマホで入力するサーバーの URL（`https://<Mac の名前>.<tailnet>.ts.net`）が表示されます。
- サインインできるのは、Mac の Tailscale にサインインしているアカウントだけです。ほかのアカウントも許すときは `--allow user@example.com` を付けて実行します。

入るものの詳細と外し方は [mac/README.md](mac/README.md) にあります。

### 2. スマホ

1. [Releases](https://github.com/k02miu/hermes-client/releases/latest) から `hermes-mobile-<バージョン>.apk` をスマホに落として入れます（「提供元不明のアプリ」の許可を求められたら許可します）。
2. Tailscale のアプリが接続中であることを確かめます。
3. Hermes Mobile を開き、「サーバー URL」に Mac で表示された URL を入れて「Tailscale でログイン」を押します。

サーバーの URL は覚えておくので、次からはそのまま開けます。

## 使い方

### 会話を選ぶ

- 上の「Bot」で、使う Hermes のプロファイルを切り替えます。
- 「Bot Chat」は Bot ごとの常設の会話です。その下に最近の会話が並びます（定期実行の会話は出しません）。
- 右上の「＋」で新しい会話を始めます。
- 会話に付く「実行中」は、Hermes 側で 5 分以内に動きがあったという印です。

### 会話する

- 下の欄に指示を書いて送ります。Hermes が作業中に送った指示は順番に処理されます。
- 作業中は送信ボタンが停止ボタンになり、押すと止められます。
- Desktop で同じ会話を開いていれば、どちらで送った内容も両方に出ます。

### Hermes からの質問に答える

- 選択肢付きの質問はカードで出ます。選択肢を押すと回答します。「おすすめ」は Hermes が推している選択肢です。
- 複数選べる質問では、選んでから「選んだ N 件で回答」を押します。
- 選択肢にない答えは「ほかの回答を入力」に書きます。
- コマンド実行の承認は、画面下からシートで出ます。
- Desktop から始めた会話で、スマホがその会話を開く前に出た質問は、スマホには出ません（Hermes が後から来た相手に送り直さないため）。Desktop で答えてください。

### ファイルを見る

返答の中で Hermes が `MEDIA:<パス>` で渡したファイルは、カードで表示されます。「プレビュー」を押すと、HTML・画像・テキストをその場で開きます。HTML の中のリンクはブラウザで開きます。

### 表示

- 右上のボタンで、テーマを「端末の設定に合わせる／ライト／ダーク」から選べます。
- 折りたたみスマホを開くと、左に一覧、右に会話の 2 画面になります。閉じると 1 画面です。
- タブレットでは会話を横に並べられます（縦向きで 2 列、横向きで 3 列まで）。
  - 会話の右上の「列を増やす」で列を足し、「✕」で閉じます。
  - 一覧で選んだ会話は、最後に触った列（上に色の線が付いた列）に開きます。
  - 一覧は左上のボタンで畳めます。畳んでいるときは、空いた列に一覧が出ます。
  - 向きを変えて列が収まらなくなったときは、最後に触った列から遠い列を閉じます。
- 表は列の中身に合わせて幅を決め、画面に収まらないときは横にスクロールします。

### うまくいかないとき

- **つながらない／サインインできない**：スマホの Tailscale が接続中か、Mac がスリープしていないか、Hermes Desktop が起動しているかを確かめてください。
- **Mac 側の状態**：ログは `~/Library/Logs/HermesMobile/` にあります。`install.sh` はもう一度実行しても大丈夫です（スマホのサインインは切れません）。
- **最新が出ない**：アプリを一度裏に回して戻すか、会話を開き直すと取り直します。通信が切れたときは自動でつなぎ直します。

## 開発

```sh
./gradlew :app:assembleDebug     # debug 版（パッケージ名 io.github.k02miu.hermesmobile.debug、アプリ名「Hermes Dev」）
```

- JDK 17 と Android SDK（compileSdk 37）が必要です。
- debug 版はリリース版と別のアプリとして入るので、並べて使えます。

### リリース

`v` で始まるタグを push すると、GitHub Actions が署名付きの APK と Mac 用の `hermes-mobile-mac.tar.gz` を作り、Releases に載せます。

```sh
git tag -a v0.1.3 -m "Hermes Mobile 0.1.3"
git push origin v0.1.3
```

署名の鍵はリポジトリの Secrets（`HERMES_KEYSTORE_BASE64`、`HERMES_KEYSTORE_PASSWORD`）から読みます。手元でリリース版を作るときは、環境変数 `HERMES_KEYSTORE_FILE` と `HERMES_KEYSTORE_PASSWORD` を設定して `./gradlew :app:assembleRelease` を実行します。
