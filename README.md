# EPlayer

Google TV / Android TV 向けの Android アプリです。テレビのランチャーから起動でき、HTTPS 上のテキストファイルを URL で読み込みます。

## 開発環境

- Android Studio (Android SDK 36)
- JDK 17
- Gradle 8.13 以降

Android Studio でこのディレクトリを開き、Gradle 同期後に Google TV または Android TV のエミュレーターへ実行してください。リモコンで URL 欄へ移動し、URL を入力して「読み込む」を選ぶと内容を表示します。

現在の読み込み実装は HTTPS の UTF-8 テキストを対象とし、2 MiB まで表示します。通信処理は UI スレッド外で実行します。
