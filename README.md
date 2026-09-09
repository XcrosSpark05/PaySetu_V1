# PaySetu

**PaySetu** is an offline-first, zero-trust financial Android application designed to facilitate secure monetary transactions even without an active internet connection.

It achieves this by combining Google's Nearby Connections API for proximity payments, an SMS Gateway for remote offline transfers, and rigorous cryptographic signatures to verify ledgers.

## Features

- **Zero-Trust Offline Payments (Proximity)**: Transact securely without the internet using the Google Nearby Connections API, powered by a cryptographic handshake and nonce challenge.
- **SMS Gateway for Remote Offline Payments**: Use SMS as a secure transport layer to route offline payments, ideal for areas with no internet or for feature phone compatibility.
- **Local Ledger & Consensus Sync**: A local Room database stores the ledger instantly. When connectivity returns, the `SyncManager` performs a two-way sync with Firebase Firestore to restore absolute truth.
- **Merchant Firewall (Safe Harbor)**: A dispute resolution protocol allowing merchants to instantly void fraudulent transactions and block malicious actors.
- **Hardware Anti-Cloning**: User sessions are bound to the hardware `ANDROID_ID`, preventing multi-device cloning attacks.

## Setup and Installation

### Prerequisites

1.  **Android Studio**: Iguana (2023.2.1) or newer is recommended.
2.  **Java Development Kit (JDK)**: JDK 17 or higher.
3.  **Physical Android Device**: Simulators cannot run Google Nearby Connections or full SMS Gateway APIs correctly. You will need physical Android devices (API 31+) to test offline functionalities.
4.  **Firebase Account**: Required to configure the backend Firestore and Authentication.

### 1. Clone the Repository

```bash
git clone https://github.com/karantrivedi575/CODEAMBLE-ZEN-T01-PaySetu.git
cd CODEAMBLE-ZEN-T01-PaySetu
```

### 2. Configure Firebase

1.  Navigate to the [Firebase Console](https://console.firebase.google.com/).
2.  Create a new Firebase Project and add an Android app.
3.  Register the app with the package name: `com.paysetu.app`.
4.  Download the `google-services.json` file.
5.  Place the `google-services.json` file into the `app/` directory of the project.
6.  In the Firebase Console, enable:
    -   **Firestore Database** (Create in production or test mode).
    -   **Authentication** (Enable Google Sign-in provider).

### 3. Build the Project

1.  Open **Android Studio**.
2.  Select **Open an existing Project** and navigate to the `PaySetu` directory.
3.  Allow Gradle to sync automatically. Ensure that all dependencies are resolved.
4.  If prompted, upgrade the Android Gradle Plugin to match your Android Studio version.

## Run Instructions

1.  Connect your physical Android device to your computer via USB (ensure **Developer Options** and **USB Debugging** are enabled).
2.  In Android Studio, select your physical device from the target device dropdown at the top.
3.  Click the **Run** button (green play icon) or press `Shift + F10`.
4.  Once the app launches on your device, you will be prompted to grant permissions. **You must accept the following permissions for the app to function properly:**
    -   Nearby Devices (Bluetooth / Wi-Fi Direct)
    -   Location (Required for device discovery)
    -   SMS (Send and Receive)
    -   Camera (If QR code scanning is used)
5.  Sign in with Google, and your device will be bound to your account.
6.  **To test offline transactions**, install the app on a second physical device and attempt a Nearby transfer or SMS transfer while both devices have Wi-Fi and Mobile Data disabled.
