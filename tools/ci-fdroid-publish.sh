#!/usr/bin/env bash
# Compila, firma y publica la app en el repo F-Droid (https://codeberg.org/arnyminerz/fdroid).
#
# Uso: tools/ci-fdroid-publish.sh stable|nightly <nombre-de-version>
#   stable   assembleMainlineRelease, applicationId original, firmado con apksigner
#   nightly  assembleMainlineNightly, applicationId *.nightly, versionCode = n de commits
# Entorno (secretos):
#   APK_KEYSTORE_B64 APK_KEYSTORE_PASSWORD APK_KEY_ALIAS
#   FDROID_KEYSTORE_B64 FDROID_KEYSTORE_PASSWORD FDROID_KEY_ALIAS FDROID_REPO_TOKEN
# Opcional: FDROID_REPO (por defecto arnyminerz/fdroid), KEEP_VERSIONS (por defecto 3)
set -euo pipefail

kind="${1:?uso: $0 stable|nightly <version>}"
version="${2:?falta la version}"
FDROID_REPO="${FDROID_REPO:-arnyminerz/fdroid}"
KEEP_VERSIONS="${KEEP_VERSIONS:-3}"
for v in APK_KEYSTORE_B64 APK_KEYSTORE_PASSWORD APK_KEY_ALIAS \
         FDROID_KEYSTORE_B64 FDROID_KEYSTORE_PASSWORD FDROID_KEY_ALIAS FDROID_REPO_TOKEN; do
    : "${!v:?falta el secreto $v}"
done

root=$(pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$APK_KEYSTORE_B64" | base64 -d > "$work/apk.jks"
printf '%s' "$FDROID_KEYSTORE_B64" | base64 -d > "$work/fdroid.jks"

# --- 1. Compilar y firmar ---------------------------------------------------
# El wrapper de Gradle trae networkTimeout=10000 (10 s) y no reintenta: en el runner local la descarga de la
# distribucion fallaba por timeout. Se sube el limite solo para este build (skip-worktree evita el flag "dirty").
sed -i 's/^networkTimeout=.*/networkTimeout=300000/' gradle/wrapper/gradle-wrapper.properties
git update-index --skip-worktree gradle/wrapper/gradle-wrapper.properties
for attempt in 1 2 3; do
    ./gradlew --version >/dev/null && break
    [ "$attempt" = 3 ] && { echo "No se pudo descargar Gradle tras 3 intentos" >&2; exit 1; }
    echo "Descarga de Gradle fallida (intento $attempt), reintentando..." >&2
    sleep 30
done

case "$kind" in
nightly)
    ./gradlew assembleMainlineNightly --stacktrace \
        -Dnightly_store_file="$work/apk.jks" \
        -Dnightly_store_password="$APK_KEYSTORE_PASSWORD" \
        -Dnightly_key_alias="$APK_KEY_ALIAS" \
        -Dnightly_key_password="$APK_KEYSTORE_PASSWORD"
    built=$(ls app/build/outputs/apk/mainline/nightly/*.apk | head -n1)
    cp "$built" "$work/signed.apk"
    ;;
stable)
    ./gradlew assembleMainlineRelease --stacktrace
    unsigned=$(ls app/build/outputs/apk/mainline/release/*.apk | head -n1)
    build_tools=$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?ANDROID_HOME no definido}}"/build-tools/* | sort -V | tail -n1)
    "$build_tools/zipalign" -f -p 4 "$unsigned" "$work/aligned.apk"
    KS_PASS="$APK_KEYSTORE_PASSWORD" "$build_tools/apksigner" sign \
        --ks "$work/apk.jks" --ks-key-alias "$APK_KEY_ALIAS" \
        --ks-pass env:KS_PASS --key-pass env:KS_PASS \
        --out "$work/signed.apk" "$work/aligned.apk"
    "$build_tools/apksigner" verify "$work/signed.apk"
    ;;
*) echo "kind debe ser stable o nightly" >&2; exit 1 ;;
esac

# --- 2. Preparar el repo F-Droid ---------------------------------------------
if ! command -v fdroid >/dev/null 2>&1; then
    pip install --quiet --user fdroidserver || pip install --quiet --break-system-packages fdroidserver
    export PATH="$HOME/.local/bin:$PATH"
fi

repo_dir="$work/fdroid"
git clone -q "https://x-access-token:${FDROID_REPO_TOKEN}@codeberg.org/${FDROID_REPO}.git" "$repo_dir" 2>/dev/null \
    || git init -q "$repo_dir"
cd "$repo_dir"
git config user.name "CODEBERG CI"
git config user.email "noreply@nodomain.nodomain"
mkdir -p repo metadata

# config.yml tiene contrasenas: solo existe en el workdir (esta en .gitignore)
cat > config.yml <<CFG
repo_url: https://codeberg.org/${FDROID_REPO}/raw/branch/main/repo
repo_name: Gadgetbridge (arnyminerz)
repo_description: Gadgetbridge de arnyminerz (fork con modificaciones). Estables = tags de upstream; nightly = releases del fork.
archive_older: 0
keystore: $work/fdroid.jks
repo_keyalias: $FDROID_KEY_ALIAS
keystorepass: $FDROID_KEYSTORE_PASSWORD
keypass: $FDROID_KEYSTORE_PASSWORD
keydname: CN=fdroid.arnyminerz, OU=F-Droid, O=ArnyminerZ, C=ES
CFG
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] && echo "sdk_path: ${ANDROID_HOME:-$ANDROID_SDK_ROOT}" >> config.yml
printf 'config.yml\n*.jks\n' > .gitignore

# --- 3. Anadir el APK, podar versiones antiguas y regenerar el indice ----------
safe_version=$(printf '%s' "$version" | tr -c 'A-Za-z0-9._-' '_')
cp "$work/signed.apk" "repo/gadgetbridge_${kind}_${safe_version}.apk"
for k in stable nightly; do
    ls -t repo/gadgetbridge_${k}_*.apk 2>/dev/null | tail -n +"$((KEEP_VERSIONS + 1))" | xargs -r rm -f --
done
fdroid update --create-metadata --clean

# --- 4. Publicar (un unico commit: el repo es derivado y los APK pesan) --------
git checkout -q --orphan publish
git add -A
git commit -q -m "Publish ${kind} ${version}"
git push --force "https://x-access-token:${FDROID_REPO_TOKEN}@codeberg.org/${FDROID_REPO}.git" publish:main
echo "Publicado ${kind} ${version} en https://codeberg.org/${FDROID_REPO}"
cd "$root"
