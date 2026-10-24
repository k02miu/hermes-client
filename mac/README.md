# Hermes Mobile — Mac 側のセットアップ

Android アプリ（Hermes Mobile）から、この Mac の Hermes に Tailscale 経由でつなぐための一式です。
スマホは Tailscale のアカウントでそのままサインインでき、パスワードは要りません。

## 前提

- Hermes Desktop（Hermes Agent が `~/.hermes` にある状態）
- Tailscale の Mac アプリにサインイン済みで、MagicDNS が有効
- スマホも同じ tailnet に参加している

## 入れる

[Releases](https://github.com/k02miu/hermes-client/releases/latest) の `hermes-mobile-mac.tar.gz` を使います。

```sh
curl -fsSL https://github.com/k02miu/hermes-client/releases/latest/download/hermes-mobile-mac.tar.gz | tar xz
./hermes-mobile-mac/install.sh
```

- 最初の 1 回だけ、終わったら Hermes Desktop を再起動してください（プラグインを読み込ませるため）。
- サインインを許すのは、Mac で Tailscale にサインインしているアカウントです。ほかのアカウントも許すときは `--allow user@example.com` を付けます（複数可）。
- `--dry-run` で、何を変えるかだけ表示します。
- 何度実行しても大丈夫です。署名用の秘密鍵は最初に作ったものを使い続けるので、スマホのサインインは切れません。

終わると、スマホで入力するサーバーの URL（`https://<Mac の名前>.<tailnet>.ts.net`）が表示されます。

## 入るもの

| もの | 場所 | 役割 |
| --- | --- | --- |
| `tailscale-auth` プラグイン | `~/.hermes/plugins/tailscale-auth` | Hermes のダッシュボード認証に「Tailscale でサインイン」を足す |
| 設定と秘密鍵 | `~/.hermes/tailscale-auth.json`（本人のみ読み書き可） | 許可するアカウント、公開 URL、署名用の秘密鍵 |
| `hermes-ts-auth` | `~/Library/Application Support/HermesMobile/bin` | `/ts-auth` で Tailscale の本人情報を受け取り、60 秒だけ有効なサインイン用コードを発行する |
| `hermes-tailscale-sync` | 同上 | Hermes の待ち受けポートは起動のたびに変わるので、`tailscale serve` の転送先を追従させる |
| LaunchAgent 2 つ | `~/Library/LaunchAgents/dev.kamiya.hermesmobile.*.plist` | ログイン時に上の 2 つを起動し、止まったら 30 秒後に起動し直す |

Hermes の設定は `plugins.enabled` に `tailscale-auth` を足し、`dashboard.public_url` を設定するだけです。
公開は `tailscale serve`（tailnet 内のみ）で、インターネットには出しません。

ログは `~/Library/Logs/HermesMobile/` にあります。

## 外す

```sh
./hermes-mobile-mac/uninstall.sh          # 秘密鍵は残す
./hermes-mobile-mac/uninstall.sh --purge  # 秘密鍵も消す（スマホは全部サインインし直し）
```
