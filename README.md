<div align="center"><img width="250" height="250" alt="Image" src="https://github.com/user-attachments/assets/76ae44a2-00a7-453a-8b75-595f184bd7a2" /></div>
<h1 align="center">Odin Data Vault</h1>
<h3 align="center">(Java + SQLite3 + Argon2id + AES-GCM + Post Quantum Resistant)</h3>

<h4 align="center">Keeping secrets safe. Since April 2026</h4>

<div align="center"><img width="520" height="310" alt="Image" src="https://github.com/user-attachments/assets/676fc46a-396f-4269-8ee4-d5db490f4f9d" /></div>

## Overview

Odin is a **post-quantum resistant encrypted data vault** built in Java Swing with some C libraries. Designed from the bottom up with privacy and security concepts in mind while remaining lightweight and easy to run with only a standard JDK 17+ with dependencies into one neat package (.jar/.vbs). 

All stored data is encrypted using **AES-256-GCM** (authenticated encryption), with keys derived via **Argon2id** from a user-provided master password. Sensitive data read into in memory then zero'd out immediately, and passwords are only decrypted when explicitly requested, minimizing exposure. AES-256-GCM is recognized by NIST as **post-quantum resistant**.

---

## 🧠 Who Are We and Purpose

Originally a **University of Missouri–Saint Louis (UMSL) Java** class project I coded for an Object Orientated Coding Class's end of semester project. I wanted to do a project on a subject I'm passionate about, which is **privacy and security**. In sticking with privacy and security I built Odin's predecessor to demonstrate cryptographic best practices including authenticated encryption, secure key derivation, memory wiping, and safe storage. It has since grown into a full blown ambitious open-source project targeting all operating systems that supports Java 17+. We(I at the moment) are NOT a business or for profit, **we are for privacy and security**. We may offer subscription tiers in the future but that will include safe storage solutions and better small business tools for multi-user setups and for people that show a desire for such a thing. My Goal is to give everyone world class **privacy, care, and security** at a fraction of the price.

Upon class project completion, the project was compared to Bitwarden, after reading an article about Bitwarden. With a the surprised look on my face, I said "I just built Bitwarden!". I found that Odin implements a very similar but with stronger modern algorithms across the board by default:

| Feature        |  Bitwarden (April 2026)   | Odin Data Vault             |
|----------------|---------------------------|-----------------------------|
| Key Derivation | PBKDF2-SHA256 (Default)   | **Argon2id**                |
| Encryption     | AES-256-CBC + HMAC-SHA256 | **AES-256-GCM**             |
| Language       | C# / TypeScript           | **Java / C libraries**      |
| Import/Export  | PBKDF2-SHA256 Only        | **Argon2id** at any setting |

---
<div align="center">

<figure>
  <img width="607" height="408" alt="Image" src="https://github.com/user-attachments/assets/83093a66-8b2f-449d-971f-4277198e2d85" />
  <figcaption>Main vault view</figcaption> 
</figure>

---

<figure>
  <img width="610" height="239" alt="Image" src="https://github.com/user-attachments/assets/5bd6e4de-cf57-4d64-ad7a-243184abbaa9" />
  <figcaption>What the data looks like stored. All data is not readable without the key.</figcaption>
</figure>

</div>

---

## 🖥️ Features and Design

**[ What sets us apart from others: ]**
* **Stronger and more Modern** whether it's your backup vaults or your main vault, know that you are securing it safely with brute force resistant practices of the highest grade, when setting to HIGH or PARANOID modes. We encrypt **all backups to the same level** as your main vault it can from. 
* **Documents and Picture Storage** with a builtin viewer for images and PDF documents, you can keep your sensitive documents like DD214, Passport documents, Driver License, and more stored and viewable in one place. 
* **Binary Keys storage** Got RAW binary keys to store and find it hard to store them ease without manual conversion to hex/base64? We can help!
* **No Master Password Storage:** Master password is never saved, however note that with multi-user mode, there is a encrypted shared vault key.
* **Multi-User mode** not just one user can login and use the vault (Shared encryption key) (Good for legacy accounts or small business)
* **Cross-platform support (Windows / Linux / macOS)** Java is cross-platform compatible and this project is devoted to keeping it that way.
* **Standalone compiled .jar executable**
* **Encryption:** AES-256-GCM (confidentiality + integrity) (Post Quantum Resistant)
* **Key Derivation:** Argon2id with random salt (stored in database) 
* **Argon2id:** hybrid variant, resistant to both GPU and side-channel attacks (parameters stored in database)
* **Secret Handling:** All secrets are stored in RAM as a `byte[]` or `char[]`, wiped from memory after use or at graceful exit/close
* **Per-entry IV:** Each username and password sets use a unique random IV making all data look unique when encrypted

**[ Vault Standards: ]**
* Search/filter functionality
* In **single-user mode:** keys are only derived at runtime and never persisted to disk in any form
* In **multi-user mode:** shared keys are stored encrypted only, but never in a decryptable state at rest
* Sensitive data is **scrubbed/zero'd from memory** as soon as it is no longer needed, minimizing the in-memory exposure window

* **Master Password Prompt at start-up**
* **Create vault at first start-up**
* **Entry controls:** Add (tag/url, username, password), delete, copy and reveal passwords
* **On-demand Decryption:** Passwords are only decrypted when requested to copy or show
* **All data is encrypted:** Using AES256-GCM which is Post Quantum Resistant
* **Password Generator**

* **Shutdown Hook:** Master password is cleared from memory before program is shutdown if NOT by `kill -9` or `Force End`
* **Idle Session Timeout:** App will close if idle for 10 minutes, locking the vault

* **Light and Dark modes** Got to protect your eyes.
* **IMPORT/EXPORT** Import your vault from Bitwarden or backup/restore your Logins/Notes/Passkeys/SSHkeys/VPNkeys NOT Local encrypted Data Files **Yet**.

---

## 💾 Storage

* Database: `vault.db` (SQLite3)
* Tables:

  * `vault`: stores encrypted (tag, username, password, data), and IV
  * `users`: stores username, role, wrapped_vk(multi-user), user_salt, argon2_parameters
  * `meta`: stores vault_salt, database_version, database_type, and future metadata
  * `server`
  * `folders`

Tags, Usernames, and Passwords are stored as encrypted binary blobs.

---

## 🖥️ Platforms Supported

    ✅ Debian 11+
    ✅ Ubuntu 20.04/22.04+
    ✅ Linux Mint 20+
    ✅ Redhat
    ✅ macOS
    ✅ Windows 7/10/11
    ✅ Pretty much anything that can run Java JDK/JRE 17+ (Features may vary)

No external database or installer required, unless you want it.

---

## ⚠️ Limitations

* Memory may not be fully cleared, always a risk, but data is always encrypted on disk. (Work around: turn off use of RAM swap space)(Reboot/Shutdown of your machine clears the memory)
* Will not save you from keyloggers or other kinds of malware
* What Odin helps protect you from: if the data vault were physically or digitally stolen Odin should prevent or slowdown the hacker. This is all dependent on which security level, type, and Master Password strength you choose and how much the hacker wants in or can afford. I cannot guarantee everything here but I can tell you we give the best effort with an ever evolving security level, so long as the project is supported, as me myself, I want to keep my data safe as well.

---

## 🚀 Future Improvements

**[ New Features ]**
* Multiple item delete
* Passphrase Generator
* "folders":  
* "totp":

**[ Big Ticket Items ]**
* Browser Extension for Firefox and Chrome **(Looking into but maybe not be up to the level of security and privacy worth doing)**
* iOS App
* Local encrypted file storage - Store encrypted documents and photos

* // Make change to changing vault key to add in redo server table (Not used yet)

**[[ Long-term Goal ]]**
**[[[ Server-Based Option ]]]**
* Modeling after the leader in Password Vaults but with more tactical and technical improvements.
* All encryption and key generation happens locally on the user's device, data is always encrypted before it ever touches a network
* The master password never leaves the device in any form, not even hashed or partially transmitted
* Encryption keys are derived and held client-side only, the server is just a locked-down storage device with no ability to decrypt your vault
* In single-user mode, keys are only derived at runtime and never persisted to disk in any form
* In multi-user mode, shared keys are stored encrypted only, but never in a decryptable state at rest

**[[[ Storage-Server ]]]**
* Sensitive data is scrubbed/zero'd from memory as soon as it is no longer needed, minimizing the in-memory exposure window
* On server encrypted data partitions, running the whole server and the encrypted data vaults on fully encrypted partitions.
* Temporary logs only on RAM.
* Production servers maybe Read-Only.
* Vault data stored on disk is always encrypted, there is no plaintext-at-rest state under any condition

---

## INSTALL:
1) Download the latest released .jar package files off of github at https://github.com/stormtheory/odin-data-vault/releases and install on your system.

          #### Windows/Linux/MacOS ####
          # Download then execute like normal or use Linux command:

          java -jar OdinDataVault-*.jar

2) Manual Install without Package Manager, run commands:

    Download the zip file of the code, off of Github. This is found under the [<> Code] button on https://github.com/stormtheory/odin-data-vault.

    Extract directory from the zip file. Run the following commands within the directory.

        #/In Folder Requirements
          Odin.java
          Yggdrasil.java
          Futhark.java
          Thor.java
          Mimir.java
          *.java
          lib/sqlite-jdbc-3.53.0.0.jar
          lib/argon2-jvm-2.12.jar
          lib/bcprov-jdk18on-1.84.jar
          lib/*
          bin/
          icons/

        # Linux Install or edit code:
            cd odin-data-vault
                ./build.sh -br  # Build and Run

                # or

                ./build.sh -r  # Run
            

        # Windows Install or edit code:
                .\run.bat -br # Build and Run

                # or

                .\run.bat           
              

## RUN:
### run the local App

        # Linux:
            cd odin-data-vault
            ./build.sh -r

        # Windows:
            Within the folder run command:
            .\run.bat

## Create .jar file, run commands:
  ✔ Works on all platforms
  ✔ No classpath needed
  ✔ No extra files

  Download the zip file of the code, off of Github. This is found under the `[<> Code]` button on `https://github.com/stormtheory/odin-data-vault`.

  Extract directory from the zip file. Run the following commands within the directory.

  On your system, for windows run the `.\run.bat -j` and for Linux run `./build.sh -j`

---

# Using Netbeans:
### What a NetBeans user needs to do
    YourProject/
    ├── src/
    │   └── icons/
    │   |    ├── icon_16.png
    │   |    ├── icon_32.png
    │   |    └── icon_256.png
    |   └── Odin.java
    |   └── Yggdrasil.java
    |   └── *.java
    └── build/

   1. `File` >> `New Project` >> 
      `Java with ANT` >> `Java Application` >> `NEXT` >>
      Project Name: `JavaVault` (or whatever) >> `Select your locations` >> `Deselect Create Main Class` >> Click `Finish`
            
   2. Drag and drop the files below into your Source Packages under \<default package>:
      `Yggdrasil.java`
      `Odin.java`
      `IdleTimeoutManager.java`

   3. Add the needed Libraries
      In NetBeans:
         Right-click `Libraries` >> Click `Add JAR/Folder` >> Select:`sqlite-jdbc-3.53.0.0.jar`
         Right-click `Libraries` >> Click `Add JAR/Folder` >> Select:`argon2-jvm-2.12.jar`
         Right-click `Libraries` >> Click `Add JAR/Folder` >> Select:`bcprov-jdk18on-1.84.jar`

   4. May need to add:
      Add JVM option in NetBeans
        Right-click project (JavaVault)
          `Properties`
          Go to:
          `Run`
          Look for a large box called `[VM Options]`, copy and paste in:
               `--enable-native-access=ALL-UNNAMED`
          If you have a locked down (noexec) /tmp directory you will also need to add:
               `-Djava.io.tmpdir=.`

   5. Click the green Play Button (`Run Project`)
   6. Select Odin as your main class

---

## Database Versions
**[ 0 ]** [Current]
* Beta: |Argon2id|AES256-GCM|SALT|IV|11 slots of data| Testing of new database ideas and expanding, expect to have to rebuild if something changes in a newer version, so keep your older versions until tested.

## Encryption

  **[ Argon2 ]**
  The unifying principle: the password/passphrase is just a human-memorable seed that is fed into Argon2id which does the work of turning low-entropy (less complex) human input into high-entropy (complex) key material that costs an attacker real money to brute-force. Not every hardware can handle the overhead or have a real need for a lot more overhead. Use the below Strength Levels to find what might be right for you. As hardware gets to be more readily available and with Post Quantum fears I would at least HIGH, but with experts saying we maybe 10 to 20 years away from Quantum Computing. 

  **[ Argon2 - Strength Levels ]**
  * ++ Level → Only Recommended Uses ++
  * MINIMUM  → rate-limited online service, low-value data
  * WARDEN   → Recommended for mobile and lower-end hardware
  * BALANCED → typical application credentials
  * HIGH     → the RFC 9106 authors' explicit recommendation for sensitive credentials
  * PARANOID → Vault-grade master key derivation, exceeds all published standards - you derive it rarely, so you can afford to make it brutal

<img width="350" height="275" alt="Image" src="https://github.com/user-attachments/assets/73c6bf6b-3ec3-468e-ae1a-0ee700eee965" />
<img width="980" height="660" alt="Image" src="https://github.com/user-attachments/assets/c87def31-8ae7-4deb-9eae-e5d00fac786e" />

## Decryption

  **[ Single User ]**
  User types master password
          ↓
  PBKDF2/Argon2 + salt (stored in DB) → AES key (lives only in RAM)
          ↓
  Session ends → key gone forever

  **[ Multi User ]**
  User 1 password → Argon2id → verify login
                            ↓
                      PBKDF2/Argon2 → User 1's personal key → decrypts their copy of the shared vault key
                                                                      ↓
                                                            Shared AES key (in DB, encrypted)
                                                                      ↓
                                                                Vault data

  Each user has their own salt + Argon2 hash + wrapped copy of the shared key
  Compromising one user's password only exposes their wrapped key, not others
  Adding/removing a user just means re-wrapping the shared key, not re-encrypting the whole vault

## Libraries used
* Java JDK 25
* Java Swing

C Libraries **Already Baked-in**:
  * `sqlite-jdbc-3.53.0.0.jar`
  * `argon2-jvm-2.12.jar`
  * `argon2-jvm-nolibs-2.12.jar`
  * `bcprov-jdk18on-1.84.jar`
  * `jna-5.18.1.jar`
  * `json-20251224.jar`
  * `pdfbox-3.0.7.jar`


