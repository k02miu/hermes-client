# Hermes Mobile — Linux 側のセットアップ

Android アプリ（Hermes Mobile）から、この Linux マシンの Hermes に Tailscale 経由でつなぐための一式です。
スマホは Tailscale のアカウントでそのままサインインでき、パスワードは要りません。
Bot Desktop（Bot が操作する画面）は Linux の Hermes にしかないので、スマホから使うにはこのセットアップが要ります。

## 前提

- Hermes Agent が `~/.hermes` にあり、gateway がサービス（`hermes-gateway`）として動いている
- Tailscale にサインイン済みで、MagicDNS と HTTPS 証明書が有効
- systemd のユーザーサービスが使える
- スマホも同じ tailnet に参加している

## 入れる

[Releases](https://github.com/k02miu/hermes-client/releases/latest) の `hermes-mobile-linux.tar.gz` を使います。

```sh
sudo tailscale set --operator=$USER   # 最初の 1 回だけ
curl -fsSL https://github.com/k02miu/hermes-client/releases/latest/download/hermes-mobile-linux.tar.gz | tar xz
./hermes-mobile-linux/install.sh
```

- 1 行目は、sudo なしで `tailscale serve` を変えられるようにする設定です。済んでいないと、インストーラは何も変えずに止まってこのコマンドを案内します。
- 最初の 1 回だけ、終わったら gateway を再起動してください（プラグインを読み込ませるため）：`systemctl --user restart hermes-gateway`
- サインインを許すのは、このマシンで Tailscale にサインインしているアカウントです。ほかのアカウントも許すときは `--allow user@example.com` を付けます（複数可）。
- `--dry-run` で、何を変えるかだけ表示します。
- 何度実行しても大丈夫です。署名用の秘密鍵は最初に作ったものを使い続けるので、スマホのサインインは切れません。

終わると、スマホで追加するサーバーの URL（`https://<マシンの名前>.<tailnet>.ts.net`）が表示されます。

## 入るもの

| もの | 場所 | 役割 |
| --- | --- | --- |
| `tailscale-auth` プラグイン | `~/.hermes/plugins/tailscale-auth` | Hermes のダッシュボード認証に「Tailscale でサインイン」を足す |
| 設定と秘密鍵 | `~/.hermes/tailscale-auth.json`（本人のみ読み書き可） | 許可するアカウント、公開 URL、署名用の秘密鍵 |
| `hermes-ts-auth` | `~/.local/share/hermes-mobile/bin` | `/ts-auth` で Tailscale の本人情報を受け取り、60 秒だけ有効なサインイン用コードを発行する |
| `hermes-tailscale-sync` | 同上 | Hermes の待ち受けポートは起動のたびに変わるので、`tailscale serve` の転送先を追従させる |
| systemd のユーザーサービス 2 つ | `~/.config/systemd/user/hermes-mobile-*.service` | 起動時に上の 2 つを動かし、止まったら 30 秒後に起動し直す |

Hermes の設定は `plugins.enabled` に `tailscale-auth` を足し、`dashboard.public_url` を設定するだけです。
公開は `tailscale serve`（tailnet 内のみ）で、インターネットには出しません。

### どの Hermes につながるか

スマホがつながるのは gateway（`hermes-gateway` のサービス）です。gateway が動いていないと、スマホからは使えません。
Linux では、Hermes Desktop が SSH 越しに起動した Hermes も動いていることがありますが、そちらは Desktop 専用で、スマホのサインインを受け付けません（Hermes の仕様です）。会話の履歴は同じものが見えます。

ログは `journalctl --user -u 'hermes-mobile-*'` で見られます。
ログアウト後も動かし続けるには `sudo loginctl enable-linger $USER` が要ります（済んでいないときはインストーラが知らせます）。

## 外す

```sh
./hermes-mobile-linux/uninstall.sh          # 秘密鍵は残す
./hermes-mobile-linux/uninstall.sh --purge  # 秘密鍵も消す（スマホは全部サインインし直し）
```
