# EDeck

Google TV / Android TV 向けのネットワーク動画プレーヤーです。UPnP/DLNA MediaServerをLAN内で検索し、またはSMB共有へ接続し、共有内の動画を再帰検索して更新日時の新しい順に表示します。再生にはMedia3を使用し、再生位置と視聴済み状態を保存します。

## 使い方

- **UPnP/DLNAを検索**: テレビとサーバーを同じLANに接続し、検出されたMediaServerを選びます。MediaServerがContentDirectoryサービスと動画のHTTPリソースを公開している必要があります。
- **SMB共有を開く**: `smb://サーバー/share/フォルダー` の形式で入力します。ユーザー名・パスワードは接続時に使い、設定には保存しません。SMB 2.0.2以降を使用します。
- **一覧を更新**: 現在のUPnPサーバーまたはSMB共有を再検索します。
- 動画を選ぶと再生します。再生位置は次回の再生時に復元し、再生が始まると視聴済みとして表示します。

## 開発環境

- Android Studio (Android SDK 36)
- JDK 17
- Gradle 8.13

Android Studio でこのディレクトリを開き、Google TV または Android TV のエミュレーターへ実行してください。方向キーで操作対象を移動し、Enter / 決定で選びます。
