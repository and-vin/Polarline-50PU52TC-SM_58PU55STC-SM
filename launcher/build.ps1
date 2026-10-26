# Builds app\ into build\tvhome.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1 -Sdk <android-sdk> -Jdk <jdk-17> [-Keystore <file.jks>] [-StorePass <pass>]
# Needs: platforms;android-25 and build-tools;34.0.0 in the SDK.
# Without -Keystore a new key is created in keystore\ (not in git). An APK signed with another key can only
# replace an installed one after uninstalling it.
param(
    [Parameter(Mandatory)] [string]$Sdk,
    [Parameter(Mandatory)] [string]$Jdk,
    [string]$Keystore = (Join-Path $PSScriptRoot 'keystore\tvhome.jks'),
    [string]$StorePass = 'tvhome1'
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$app = Join-Path $here 'app'
$out = Join-Path $here 'build'
$bt = Join-Path $Sdk 'build-tools\34.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-25\android.jar'
$keystore = $Keystore
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

function Run([string]$exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $exe -Leaf) failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\gen", "$out\stubs", "$out\classes", "$out\dex" | Out-Null

'aapt2 compile/link'
Run "$bt\aapt2.exe" compile --dir "$app\res" -o "$out\res.zip"
Run "$bt\aapt2.exe" link -I $androidJar --manifest "$app\AndroidManifest.xml" --java "$out\gen" `
    --min-sdk-version 24 --target-sdk-version 25 -o "$out\app.unsigned.apk" "$out\res.zip"

'javac'
$stubSrc = Get-ChildItem (Join-Path $here 'stubs') -Recurse -Filter *.java | ForEach-Object FullName
# java.* from the JDK's release-8 API, android.* from android.jar (its lack of LambdaMetafactory crashes javac
# when used as bootclasspath); d8 desugars the lambdas.
Run "$Jdk\bin\javac.exe" -nowarn --release 8 -cp $androidJar -encoding UTF-8 -d "$out\stubs" @stubSrc
$appSrc = @(Get-ChildItem "$app\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -cp "$androidJar;$out\stubs" -d "$out\classes" @appSrc

'd8'
Run "$Jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
Run "$bt\d8.bat" --release --min-api 24 --lib $androidJar --classpath "$out\stubs" --output "$out\dex" "$out\classes.jar"

'package'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\app.unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
# Assets are added here, not with aapt2 -A: on Windows aapt2 stores nested paths with '\' and Android
# then does not see the folder. JPEGs are stored uncompressed (they do not shrink anyway).
$assets = Join-Path $app 'assets'
Get-ChildItem $assets -Recurse -File -Filter *.jpg | ForEach-Object {
    $name = 'assets/' + $_.FullName.Substring($assets.Length + 1).Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name,
            [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
}
$zip.Dispose()
Run "$bt\zipalign.exe" -p -f 4 "$out\app.unsigned.apk" "$out\app.aligned.apk"

if (-not (Test-Path $keystore)) {
    'new signing key (keep keystore\ - updates must be signed with the same key)'
    New-Item -ItemType Directory -Force (Split-Path $keystore) | Out-Null
    Run "$Jdk\bin\keytool.exe" -genkeypair -keystore $keystore -storepass $StorePass -keypass $StorePass -alias tvhome `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Home, O=and-vin'
}
# apksigner.jar directly: apksigner.bat cannot locate its lib\ under some long (MSIX-virtualized) paths.
$apksigner = "$bt\lib\apksigner.jar"
Run "$Jdk\bin\java.exe" -jar $apksigner sign --ks $keystore --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" --ks-key-alias tvhome `
    --out "$out\tvhome.apk" "$out\app.aligned.apk"
Run "$Jdk\bin\java.exe" -jar $apksigner verify "$out\tvhome.apk"
"built: $out\tvhome.apk ($((Get-Item "$out\tvhome.apk").Length) bytes)"
