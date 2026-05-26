#!/usr/bin/env bash
#
# Purpose:
# Secure Note is a zero-install solution for encrypting personal notes and passwords on any standard Linux system.
# The core problem it solves: you need secure local storage, but you are on a locked-down machine where installing software like KeePass, 
# Bitwarden, or any password manager is not permitted. Rather than relying on a plaintext file, a browser's saved passwords, or nothing at all, 
# Secure Note leverages tools that ship with virtually every Linux distribution and are deeply tied to core system functionality making them unlikely 
# to ever be removed: gpg, bash, zenity, vim/nano, and less.
# The workflow keeps plaintext exposure to an absolute minimum. Decrypted content never touches the disk; it lives only in /dev/shm, a memory-backed 
# RAM filesystem. You view notes through less with no terminal history leak, and the GUI passphrase prompt via zenity keeps your password off the command 
# line and out of process lists. When you are done, the plaintext is gone.
# The dependency choices are deliberate:
#
# gpg is a foundational cryptography tool present on nearly every distro, used by package managers themselves for signature verification.
# bash and less are baseline POSIX utilities.
# zenity ships with most GNOME-adjacent environments and is a common dependency for desktop scripts.
# vim (default) gives fine-grained control over swap files and undo history, making it the safer editor choice over nano.
#
#
# ==============================
# Odin Bash Secure Note 
#    (bash + zenity + gpg + less + editor{vim|nano})
# Goals:
#   - AES256 at-rest via GPG symmetric encryption
#   - View: pipe plaintext to less (no history)
#   - Edit/Create: plaintext lives only in /dev/shm (tmpfs RAM)
#   - Best-effort: avoid caching symmetric key (when supported)
#   - Recommendations for more security
#       -- use encrypted swap or 
#       -- or disable swap (sudo swapoff -a)
#       -- or mlock() via native code
#       -- There is a way written in below to use nano but vim is the recommended more secure option
#       -- Best way is to write a Rust program to:
#            [gpg --decrypt] → stdin → [Rust editor/viewer] → stdout → [gpg --symmetric] 
#
#
#  Written by StormTheory, August 2025 and now homed at https://github.com/stormtheory/odin-data-vault
#
# ==============================
#
# Editor hardening
# The script defaults to vim with a deliberately stripped invocation: -n disables the swap file entirely, -u NONE skips loading any 
# vimrc, and -i NONE prevents writing a viminfo history file. Combined with the temp file being created at 600 permissions in /dev/shm, 
# this means vim leaves no artifacts anywhere on disk during the edit session.
#
# Edit the following lines in the script:
#    EDITOR_CMD=(vim -n -u NONE -i NONE)
#    EDITOR_CHOICE=(vim)
#
# nano is available as a commented-out fallback, but it requires significantly more environment surgery to achieve a similar level of hygiene. 
# The nano invocation redirects the entire XDG config tree 
# (XDG_CONFIG_HOME, XDG_STATE_HOME, XDG_CACHE_HOME) to /nonexistent, forces NANORC to /dev/null, 
# and passes --restricted --nohelp --noconvert --nowrap --backupdir=/dev/null to suppress backups, conversion logs, and any config-driven behavior. 
# It achieves roughly the same outcome but requires trusting more moving parts, which is why vim is the recommended default.
#
# The practical difference: vim's hardening is controlled entirely by command-line flags with well-defined, auditable behavior. 
# Nano's hardening depends on environment variable overrides, which are slightly more fragile across distribution variations and shell environments.
#
#
set -euo pipefail
IFS=$'\n\t'

if [ -t 0 ] && [ -t 1 ]; then
    useTTY=true
    #echo "Launched from command line (TTY present)"
else
    useTTY=false
    ### Here for Debugging
    #DATE=$(date)
    #echo "$DATE - Launched from .desktop / GUI (no TTY)" >> /home/$USER/Desktop/launched.log
fi


APP_NAME="Secure Note"
DEFAULT_EXT=".gpg"
TMPDIR_RAM="/dev/shm"

# Hardened less: no on-disk history
export LESSHISTFILE=-
LESS_OPTS=(--no-hist)

# Hardened vim invocation:
#   -n         : no swap file
#   -u NONE    : no vimrc
#   -i NONE    : no viminfo
# Notes:
#   - We also set temp file permissions to 600.
EDITOR_CMD=(vim -n -u NONE -i NONE)
EDITOR_CHOICE=(vim)

## Uncomment below lines for use of the nano text editor.
#    EDITOR_CMD=(HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent XDG_STATE_HOME=/nonexistent XDG_CACHE_HOME=/nonexistent NANORC=/dev/null nano --restricted --nohelp --noconvert --nowrap --backupdir=/dev/null)
#    EDITOR_CHOICE=(nano)

# GPG flags for symmetric encryption / decryption:
#   --pinentry-mode loopback + --passphrase-fd 0 to supply passphrase from zenity
#   --no-symkey-cache if supported to avoid caching symmetric key
#   Strong S2K (string-to-key) settings to slow brute-force
GPG_BASE=(gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0)

# Prefer modern KDF settings. s2k-count is an encoded iteration count; adjust if desired.
# Warning: extremely high values can make decrypt slow on older hardware.
GPG_S2K=(
  --symmetric
  --cipher-algo AES256
  --digest-algo SHA256
  --s2k-mode 3
  --s2k-digest-algo SHA256
  --s2k-count 65011712
)

have_cmd() { command -v "$1" >/dev/null 2>&1; }

die() {
  zenity --error --title="$APP_NAME" --text="$1" 2>/dev/null || true
  echo "ERROR: $1" >&2
  exit 1
}

info() {
  zenity --info --title="$APP_NAME" --text="$1" 2>/dev/null || true
}

# Return 0 if gpg supports --no-symkey-cache
gpg_supports_no_symkey_cache() {
  gpg --help 2>/dev/null | grep -q -- '--no-symkey-cache'
}

get_gpg_cmd_with_optional_nocache() {
  local -a cmd=("${GPG_BASE[@]}")
  if gpg_supports_no_symkey_cache; then
    cmd+=(--no-symkey-cache)
  fi
  printf '%s\0' "${cmd[@]}"
}

prompt_password() {
  # Zenity password dialog returns plaintext passphrase to stdout.
  # In bash, this will exist briefly in memory; unavoidable in this UI design.
  zenity --password --title="$APP_NAME" --text="Enter password:" 2>/dev/null || return 1
}

choose_file_open() {
  zenity --file-selection --title="$APP_NAME - Choose file" 2>/dev/null || return 1
}

choose_file_save() {
  zenity --file-selection --save --confirm-overwrite --title="$APP_NAME - Save file" 2>/dev/null || return 1
}

choose_action() {
  # Returns: decrypt | encrypt | create
  zenity --list --radiolist \
    --title="$APP_NAME" \
    --text="Choose an action:" \
    --column="Pick" --column="Action" \
    TRUE "decrypt" FALSE "encrypt" FALSE "create" 2>/dev/null || return 1
}

confirm_edit_after_decrypt() {
  zenity --question --title="$APP_NAME" \
    --text="Open in Edit mode?\n\nYes = Edit (re-encrypt on save)\nNo = View only" \
    2>/dev/null
}

ensure_deps() {
  have_cmd zenity         || die "Missing dependency: zenity"
  have_cmd gpg            || die "Missing dependency: gpg"
  have_cmd less           || die "Missing dependency: less"
  have_cmd $EDITOR_CHOICE || die "Missing dependency: $EDITOR_CHOICE"

  [[ -d "$TMPDIR_RAM" ]] || die "Missing $TMPDIR_RAM (expected tmpfs RAM filesystem)."
}

# ==============================
# Core operations
# ==============================

decrypt_to_less() {
  local enc_file="$1"
  [[ -f "$enc_file" ]] || die "File not found: $enc_file"

  local pw
  pw="$(prompt_password)" || return 1

  # Build gpg command (with optional --no-symkey-cache)
  local -a gpgcmd=()
  while IFS= read -r -d '' x; do gpgcmd+=("$x"); done < <(get_gpg_cmd_with_optional_nocache)

  # Pipe plaintext directly to less. No plaintext file on disk.
  # shellcheck disable=SC2094
  printf '%s' "$pw" | "${gpgcmd[@]}" --decrypt -- "$enc_file" 2>/dev/null | less "${LESS_OPTS[@]}"
}

decrypt_to_editor_and_reencrypt() {
  local enc_file="$1"
  [[ -f "$enc_file" ]] || die "File not found: $enc_file"

  local pw
  pw="$(prompt_password)" || return 1

  local tmp_plain
  tmp_plain="$(mktemp "$TMPDIR_RAM/secure-note.XXXXXX.txt")"
  chmod 600 "$tmp_plain"

  # Build gpg command (with optional --no-symkey-cache)
  local -a gpgcmd=()
  while IFS= read -r -d '' x; do gpgcmd+=("$x"); done < <(get_gpg_cmd_with_optional_nocache)

  # Decrypt into /dev/shm only
  if ! printf '%s' "$pw" | "${gpgcmd[@]}" --decrypt -- "$enc_file" >"$tmp_plain" 2>/dev/null; then
    rm -f "$tmp_plain"
    die "Decryption failed (wrong password or corrupt file)."
  fi

  # Edit plaintext in RAM (no swap file, no configs)
  "${EDITOR_CMD[@]}" "$tmp_plain"

  # Re-encrypt back to the same file (atomic write via temp + mv)
  local tmp_enc
  tmp_enc="$(mktemp "$TMPDIR_RAM/secure-note.XXXXXX.gpg")"
  chmod 600 "$tmp_enc"

  if ! printf '%s' "$pw" | "${gpgcmd[@]}" "${GPG_S2K[@]}" --output "$tmp_enc" -- "$tmp_plain" 2>/dev/null; then
    rm -f "$tmp_plain" "$tmp_enc"
    die "Re-encryption failed."
  fi

  # Replace original securely (on tmpfs this is fine; on disk, mv is atomic within same FS)
  mv -f "$tmp_enc" "$enc_file"

  # Remove plaintext (still best-effort; tmpfs avoids disk remnants)
  rm -f "$tmp_plain"

  info "Saved and re-encrypted. \n $enc_file"
}

encrypt_plaintext_file() {
  local plain_file="$1"
  [[ -f "$plain_file" ]] || die "File not found: $plain_file"

  local out_file
  out_file="$(choose_file_save)" || return 1
  [[ -n "$out_file" ]] || return 1

  # Default extension if user omitted
  if [[ "$out_file" != *.* ]]; then
    out_file="${out_file}${DEFAULT_EXT}"
  fi

  local pw
  pw="$(prompt_password)" || return 1

  local -a gpgcmd=()
  while IFS= read -r -d '' x; do gpgcmd+=("$x"); done < <(get_gpg_cmd_with_optional_nocache)

  if ! printf '%s' "$pw" | "${gpgcmd[@]}" "${GPG_S2K[@]}" --output "$out_file" -- "$plain_file" 2>/dev/null; then
    die "Encryption failed."
  fi

  chmod 600 "$out_file" 2>/dev/null || true
  info "Encrypted file saved."
}

create_new_encrypted_file() {
  local out_file="$1"
  if [[ -z "$out_file" ]]; then
    out_file="$(choose_file_save)" || return 1
  fi
  [[ -n "$out_file" ]] || return 1

  if [[ "$out_file" != *.* ]]; then
    out_file="${out_file}${DEFAULT_EXT}"
  fi

  local pw
  pw="$(prompt_password)" || return 1

  local tmp_plain
  tmp_plain="$(mktemp "$TMPDIR_RAM/secure-note.XXXXXX.txt")"
  chmod 600 "$tmp_plain"

  # Start with empty file and let user edit
  : >"$tmp_plain"
  "${EDITOR_CMD[@]}" "$tmp_plain"

  local -a gpgcmd=()
  while IFS= read -r -d '' x; do gpgcmd+=("$x"); done < <(get_gpg_cmd_with_optional_nocache)

  if ! printf '%s' "$pw" | "${gpgcmd[@]}" "${GPG_S2K[@]}" --output "$out_file" -- "$tmp_plain" 2>/dev/null; then
    rm -f "$tmp_plain"
    die "Encryption failed."
  fi

  rm -f "$tmp_plain"
  chmod 600 "$out_file" 2>/dev/null || true
  info "Created encrypted file. \n $out_file"
}

usage() {
  #cat <<'EOF'
echo "
Options:
  -d [file]   Decrypt with GUI (if file omitted, chooser). View in less; option to edit.
  -r [file]   Decrypt with GUI and Read-Only.
  -i [file]   Decrypt with GUI and Edit-only.
  -e [file]   Encrypt an existing plaintext file (if file omitted, chooser).
  -c [file]   Create a new encrypted file (if file omitted, chooser) and edit in vim.
  -h          Help

Editor Command: $EDITOR_CMD

Security notes:
  - View mode pipes plaintext directly to 'less --no-hist' (no plaintext file on disk).
  - Edit/Create uses /dev/shm (tmpfs RAM). Vim is run as: vim -n -u NONE -i NONE
  - Uses gpg symmetric AES256 and attempts --no-symkey-cache if supported.
"
#EOF
}

# ==============================
# Main
# ==============================

main() {
  ensure_deps

  local mode="" file=""

  while getopts ":d:r:e:c:h" opt; do
    case "$opt" in
      d) mode="decrypt"; file="${OPTARG:-}";;
      r) mode="read-decrypt" file="${OPTARG:-}";;
      i) mode="edit-decrypt" file="${OPTARG:-}";;
      e) mode="encrypt"; file="${OPTARG:-}";;
      c) mode="create";  file="${OPTARG:-}";;
      h) usage; exit 0;;
      \?) usage; exit 2;;
    esac
  done

  # No mode? Ask via GUI
  if [[ -z "${mode}" ]]; then
    mode="$(choose_action)" || exit 1
  fi

  case "$mode" in
    decrypt)
      if [[ -z "${file}" ]]; then file="$(choose_file_open)" || exit 1; fi
      # Ask whether to edit after decrypt
      if confirm_edit_after_decrypt; then
        decrypt_to_editor_and_reencrypt "$file"
      else
        decrypt_to_less "$file"
      fi
      ;;
    read-decrypt)
      if [[ -z "${file}" ]]; then file="$(choose_file_open)" || exit 1; fi
      decrypt_to_less "$file"
      ;;
    edit-decrypt)
      if [[ -z "${file}" ]]; then file="$(choose_file_open)" || exit 1; fi
      decrypt_to_editor_and_reencrypt "$file"
      ;;
    encrypt)
      if [[ -z "${file}" ]]; then file="$(choose_file_open)" || exit 1; fi
      encrypt_plaintext_file "$file"
      ;;
    create)
      # file may be empty; create flow will chooser if needed
      create_new_encrypted_file "${file:-}"
      ;;
    *)
      die "Unknown mode: $mode"
      ;;
  esac
}

main "$@"

