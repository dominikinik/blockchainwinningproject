#!/bin/sh
# Checks that this machine has every tool the monorepo's modules and scripts/test-all.sh need, and
# installs the missing Solana-side tools (Rust, Solana/Agave CLI, Anchor via avm, Surfpool, a local
# keypair). Versions match the Dockerfile so host and devcontainer builds agree.
#
#   scripts/setup-toolchain.sh          check, then install whatever is missing
#   scripts/setup-toolchain.sh --check  check only; exit 1 if anything is missing
#
# Java, Node and Docker are only checked, not installed: they have several valid installers and
# picking one for you is more likely to conflict with what you already use. The script prints a hint.
# Safe to re-run; it never overwrites an existing toolchain or keypair.
set -eu

RUST_VERSION=${RUST_VERSION:-1.95.0}
ANCHOR_VERSION=${ANCHOR_VERSION:-1.1.2}
SOLANA_CHANNEL=${SOLANA_CHANNEL:-stable}   # Agave release channel or exact version, e.g. v3.0.0
JAVA_MIN=21
NODE_MIN=24
KEYPAIR="$HOME/.config/solana/id.json"

CHECK_ONLY=0
case "${1:-}" in
	--check) CHECK_ONLY=1 ;;
	"") ;;
	-h | --help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
	*) echo "unknown option: $1 (use --check or --help)" >&2; exit 2 ;;
esac

# Installers drop binaries here; make them visible to this run even before the shell profile reloads.
PATH="$HOME/.cargo/bin:$HOME/.avm/bin:$HOME/.local/share/solana/install/active_release/bin:$HOME/.local/bin:$PATH"
export PATH

MISSING=""
ok()   { printf '  ok       %-14s %s\n' "$1" "$2"; }
miss() { printf '  MISSING  %-14s %s\n' "$1" "$2"; MISSING="$MISSING $1"; }
have() { command -v "$1" >/dev/null 2>&1; }
# First integer in a version string, e.g. 'openjdk version "24.0.2"' -> 24, 'v24.14.1' -> 24.
major() { echo "$1" | sed -n 's/[^0-9]*\([0-9][0-9]*\).*/\1/p' | head -1; }

check_min_major() { # name min hint version-command...
	name=$1 min=$2 hint=$3
	shift 3
	if ! have "$1"; then miss "$name" "$hint"; return; fi
	v=$("$@" 2>&1 | head -1)
	if [ "$(major "$v")" -ge "$min" ] 2>/dev/null; then ok "$name" "$v"; else miss "$name" "found '$v', need >= $min. $hint"; fi
}

check_exact() { # name expected hint version-command...
	name=$1 expected=$2 hint=$3
	shift 3
	if ! have "$1"; then miss "$name" "$hint"; return; fi
	v=$("$@" 2>&1 | head -1)
	case "$v" in *"$expected"*) ok "$name" "$v" ;; *) miss "$name" "found '$v', need $expected" ;; esac
}

check_present() { # name hint version-command...
	name=$1 hint=$2
	shift 2
	if have "$1"; then ok "$name" "$("$@" 2>&1 | head -1)"; else miss "$name" "$hint"; fi
}

check_all() {
	MISSING=""
	echo "Checking toolchain:"
	check_present git "install Xcode Command Line Tools: xcode-select --install" git --version
	check_present curl "required by the installers" curl --version
	check_present docker "install Docker Desktop (uptime-db tests run postgres in Docker)" docker --version
	if have docker && ! docker info >/dev/null 2>&1; then miss docker-daemon "start Docker Desktop"; fi
	check_min_major java "$JAVA_MIN" "install a JDK >= $JAVA_MIN, e.g. https://adoptium.net" java -version
	check_min_major node "$NODE_MIN" "install Node >= $NODE_MIN, e.g. via nvm: nvm install $NODE_MIN" node --version
	check_exact rustc "$RUST_VERSION" "Rust via rustup" rustup run "$RUST_VERSION" rustc --version
	check_present cargo "Rust via rustup" cargo --version
	check_present solana "Solana/Agave CLI" solana --version
	check_present cargo-build-sbf "ships with the Solana/Agave CLI" cargo-build-sbf --version
	check_present avm "Anchor version manager" avm --version
	check_exact anchor "$ANCHOR_VERSION" "Anchor CLI via avm" anchor --version
	check_present surfpool "local Solana validator used by anchor test" surfpool --version
	if [ -f "$KEYPAIR" ]; then ok keypair "$KEYPAIR"; else miss keypair "local dev wallet at $KEYPAIR"; fi
}

is_missing() { case " $MISSING " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

install_missing() {
	echo
	echo "Installing missing Solana tools:"

	if is_missing rustc || is_missing cargo; then
		if have rustup; then
			echo "==> rustup toolchain install $RUST_VERSION"
			rustup toolchain install "$RUST_VERSION" --profile minimal --component rustfmt --component clippy
		else
			echo "==> rustup + Rust $RUST_VERSION"
			curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
				| sh -s -- -y --profile minimal --default-toolchain "$RUST_VERSION" --component rustfmt --component clippy
		fi
	fi

	if is_missing solana || is_missing cargo-build-sbf; then
		echo "==> Solana/Agave CLI ($SOLANA_CHANNEL)"
		sh -c "$(curl -sSfL "https://release.anza.xyz/$SOLANA_CHANNEL/install")"
	fi

	if is_missing avm; then
		echo "==> avm (builds from source, takes a few minutes)"
		cargo install --git https://github.com/solana-foundation/anchor avm --force --locked
	fi

	if is_missing anchor; then
		echo "==> anchor $ANCHOR_VERSION"
		# avm verifies the prebuilt binary's build provenance via the GitHub API; if that check can't
		# run (e.g. rate-limited, 403) it refuses the binary. Building from source is the safe fallback.
		avm install "$ANCHOR_VERSION" || avm install "$ANCHOR_VERSION" --from-source
		avm use "$ANCHOR_VERSION"
	fi

	if is_missing surfpool; then
		echo "==> surfpool"
		curl -sL https://run.surfpool.run/ | bash
	fi

	if is_missing keypair; then
		echo "==> local dev keypair (holds no real funds)"
		mkdir -p "$(dirname "$KEYPAIR")"
		solana-keygen new --no-bip39-passphrase --silent --outfile "$KEYPAIR"
	fi
}

check_all
if [ -z "$MISSING" ]; then echo "All tools present."; exit 0; fi
if [ "$CHECK_ONLY" -eq 1 ]; then echo "Missing:$MISSING" >&2; exit 1; fi

install_missing
echo
check_all
if [ -n "$MISSING" ]; then
	echo "Still missing:$MISSING (see hints above; Java, Node and Docker are not auto-installed)" >&2
	exit 1
fi
cat <<EOF
All tools present. Open a new shell (or add these to your profile) so they are on PATH:
  export PATH="\$HOME/.cargo/bin:\$HOME/.avm/bin:\$HOME/.local/share/solana/install/active_release/bin:\$HOME/.local/bin:\$PATH"
EOF
