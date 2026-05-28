<div align="center"><img width="256" height="256" alt="Image" src="https://github.com/user-attachments/assets/5fe094b1-86ba-42e7-9df1-2bf62791885c" /></div>
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

> [!IMPORTANT]
> As always make sure the new version works with your database before moving to the next version and always do an Encrypted Odin JSON export/backup. 

> [!WARNING]
> **Ensure you remember your Master Password as there is NO WAY TO RECOVER WITHOUT IT.** You are the only one that can get to your data period end of subject, which is the whole point of Odin.

> [!NOTE]
> If any issues or suggestions, or even just a what if crosses you please submit feedback at https://github.com/stormtheory/odin-data-vault/issues. Would love to hear from you. Enjoy the new update :)
>
> &mdash; Stormtheory

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
* **Cross-platform support (Windows / Linux / [iPhone/MacOS(Coming Soon)])** Java is cross-platform compatible and this project is devoted to keeping it that way.
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
  * `folders`: Defines the folder names and IDs for them
  * `server`: Will be used in the future for server connections for syncing

---

## 🖥️ Platforms Supported

    ✅ Debian 11+
    ✅ Ubuntu 20.04/22.04+
    ✅ Linux Mint 20+
    ✅ Redhat
    ✅ Windows 7/10/11
    ✅ Pretty much anything that can run Java JDK/JRE 17+ (Features may vary)

No external database or installer required, unless you want it.

---

## ⚠️ Limitations
> [!NOTE]
> What Odin helps protect you from: if the data vault were physically or digitally stolen Odin should prevent or slowdown the hacker. This is all dependent on which security level, type, and Master Password strength you choose and how much the hacker wants in or can afford. I cannot guarantee everything here but I can tell you we give the best effort with an ever evolving security level, so long as the project is supported, as me myself, I want to keep my data safe as well.


> [!IMPORTANT]
> What any data/password vault out there can not protect you from...
> * Yourself (insider threats, misconfiguration, weak Master Password(s))
> * Quantum decryption of today's encrypted-at-rest data (harvest now, decrypt later) (See "Argon2 - Strength Levels" below and chart for more details)
> * Amnesia - losing or forgetting your Master Password entirely
> * The keys being stolen (if your MasterPassword is found out you are compromised, the vault is open)
> * Zero-day exploits targeting the vault software itself
> * Metadata leakage (what data exists, who accesses it, when)
> * Coercion ("rubber hose cryptanalysis" - forced decryption)
> * Supply chain attacks on the vault vendor
> * A compromised endpoint reading data after it's been decrypted for legitimate use
> * Backup sprawl - copies of the data that never made it into the vault
> * Legal compulsion (warrants, subpoenas, national security letters) (This one for Odin is diffcult, becuase we can not access your data without your Master Password, therefore you have to open it.)
> * Social engineering (attackers bypass the vault by manipulating people) (With Odin, that's only you as they will need the Master Password)
> 
> The vault protects stored data. The moment data moves, gets used, or humans get involved, the attack surface explodes. Security is a system, not a product.


> [!IMPORTANT]
> Memory (RAM) may not be fully cleared, always a risk, but data is always encrypted on disk.
> (Work around: turn off use of RAM swap space)(Reboot/Shutdown of your machine clears the memory)
---

## 🚀 Future Improvements

**[ New Features ]**
- [X] Multiple item delete
- [X] "folders"
- [X] Trashcan - deleted items lands here.
- [ ] Passphrase Generator
- [ ] "totp":

**[ Big Ticket Items ]**
- [-] iOS App (work started)
- [-] MacOS   (work started)
- [-] Server Sync
- [ ] Android App
- [ ] Browser Extension for Firefox and Chrome **(Looking into but maybe not be up to the level of security and privacy worth doing)**
- [ ] Local encrypted file storage - Store encrypted documents and photos

**[ MISC ]**
- [ ] Make changes to "changing vault key" to add in redo server table (Not used yet)

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

<img width="450" height="320" alt="Image" src="https://github.com/user-attachments/assets/73c6bf6b-3ec3-468e-ae1a-0ee700eee965" />
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

---
---
<div align="center"><img width="256" height="256" alt="Image" src="https://github.com/user-attachments/assets/bc63a7e7-5747-49b4-b3c5-f8bd4f4e0cc7" /></div>

---
<h1 align="center">Odin Bash Secure Note</h1>
(bash + zenity + gpg + less + editor{vim|nano})
### Purpose:
Secure Note is a zero-install solution for encrypting personal notes and passwords on any standard Linux system.
The core problem it solves: you need secure local storage, but you are on a locked-down machine where installing software like KeePass, 
Bitwarden, or any password manager is not permitted. Rather than relying on a plaintext file, a browser's saved passwords, or nothing at all, 
Secure Note leverages tools that ship with virtually every Linux distribution and are deeply tied to core system functionality making them unlikely 
to ever be removed: gpg, bash, zenity, vim/nano, and less.
The workflow keeps plaintext exposure to an absolute minimum. Decrypted content never touches the disk; it lives only in /dev/shm, a memory-backed 
RAM filesystem. You view notes through less with no terminal history leak, and the GUI passphrase prompt via zenity keeps your password off the command 
line and out of process lists. When you are done, the plaintext is gone.
The dependency choices are deliberate:

gpg is a foundational cryptography tool present on nearly every distro, used by package managers themselves for signature verification. 
bash and less are baseline POSIX utilities.
zenity ships with most GNOME-adjacent environments and is a common dependency for desktop scripts.
vim (default) gives fine-grained control over swap files and undo history, making it the safer editor choice over nano.

<img width="600" height="370" alt="Image" src="https://github.com/user-attachments/assets/9e542e4c-9aea-4caa-a178-e1b6065b9f78" />

### Editor hardening
The script defaults to vim with a deliberately stripped invocation: -n disables the swap file entirely, -u NONE skips loading any 
vimrc, and -i NONE prevents writing a viminfo history file. Combined with the temp file being created at 600 permissions in /dev/shm, 
this means vim leaves no artifacts anywhere on disk during the edit session.

Edit the following lines in the script:
        EDITOR_CMD=(vim -n -u NONE -i NONE)
        EDITOR_CHOICE=(vim)

nano is available as a commented-out fallback, but it requires significantly more environment surgery to achieve a similar level of hygiene. 
The nano invocation redirects the entire XDG config tree 
(XDG_CONFIG_HOME, XDG_STATE_HOME, XDG_CACHE_HOME) to /nonexistent, forces NANORC to /dev/null, 
and passes --restricted --nohelp --noconvert --nowrap --backupdir=/dev/null to suppress backups, conversion logs, and any config-driven behavior. 
It achieves roughly the same outcome but requires trusting more moving parts, which is why vim is the recommended default.

The practical difference: vim's hardening is controlled entirely by command-line flags with well-defined, auditable behavior. 
Nano's hardening depends on environment variable overrides, which are slightly more fragile across distribution variations and shell environments.
