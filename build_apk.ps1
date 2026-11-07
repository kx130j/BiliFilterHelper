$ErrorActionPreference = "Stop"

$ProjectDir = "c:\Users\98733\AntigravityWork\Projects\BiliFilterHelper"
$AppMainDir = "$ProjectDir\app\src\main"
$BuildDir   = "$ProjectDir\app\build\direct_apk"
$OutputDir  = "$ProjectDir\app\build\outputs\apk\debug"

$JdkHome    = "C:\Program Files\Java\jdk-17"
$JavaExe    = "$JdkHome\bin\java.exe"
$JavacExe   = "$JdkHome\bin\javac.exe"
$KeytoolExe = "$JdkHome\bin\keytool.exe"
$JarExe     = "$JdkHome\bin\jar.exe"

$SdkDir        = "C:\Users\98733\AppData\Local\Android\Sdk"
$BuildTools    = "$SdkDir\build-tools\34.0.0"
$AndroidJar    = "$SdkDir\platforms\android-34\android.jar"
$Aapt2Exe      = "$BuildTools\aapt2.exe"
$D8Jar         = "$BuildTools\lib\d8.jar"
$Zipalign      = "$BuildTools\zipalign.exe"
$ApksignerJar  = "$BuildTools\lib\apksigner.jar"

$env:JAVA_HOME = $JdkHome

# Remove old .kt files so only .java files are compiled
Get-ChildItem -Path "$AppMainDir\java" -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue | Remove-Item -Force

Write-Host "==> [1/6] Cleaning and preparing build directories..."
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path "$BuildDir\res_compiled", "$BuildDir\gen", "$BuildDir\classes", "$BuildDir\dex", $OutputDir | Out-Null

Write-Host "==> [2/6] Compiling Android resources with aapt2..."
& $Aapt2Exe compile --dir "$AppMainDir\res" -o "$BuildDir\res_compiled\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> [3/6] Linking resources and generating R.java with aapt2..."
& $Aapt2Exe link `
    -I $AndroidJar `
    --manifest "$AppMainDir\AndroidManifest.xml" `
    --java "$BuildDir\gen" `
    --min-sdk-version 26 `
    --target-sdk-version 34 `
    --version-code 1 `
    --version-name "1.0.0" `
    -o "$BuildDir\unaligned.apk" `
    "$BuildDir\res_compiled\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> [4/6] Compiling Java & R.java sources with javac..."
$JavaFiles = @(
    Get-ChildItem -Path "$AppMainDir\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
    Get-ChildItem -Path "$BuildDir\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
)

& $JavacExe -encoding UTF-8 -source 11 -target 11 -classpath $AndroidJar -d "$BuildDir\classes" @JavaFiles
if ($LASTEXITCODE -ne 0) { throw "Javac compile failed" }

Write-Host "==> [5/6] Converting bytecode to DEX with d8..."
$ClassFiles = Get-ChildItem -Path "$BuildDir\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $JavaExe -cp $D8Jar com.android.tools.r8.D8 --lib $AndroidJar --min-api 26 --output "$BuildDir\dex" @ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# Add classes.dex into unaligned.apk using jar.exe
& $JarExe -uf "$BuildDir\unaligned.apk" -C "$BuildDir\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar update failed" }

Write-Host "==> [6/6] Aligning and signing APK..."
$AlignedApk = "$BuildDir\aligned.apk"
& $Zipalign -f -p 4 "$BuildDir\unaligned.apk" $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$KeystorePath = "$ProjectDir\debug.keystore"
if (-not (Test-Path $KeystorePath)) {
    & $KeytoolExe -genkeypair `
        -keystore $KeystorePath `
        -storepass android `
        -keypass android `
        -alias androiddebugkey `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

$FinalApk = "$OutputDir\BiliFilterHelper-v1.0.0.apk"
& $JavaExe -jar $ApksignerJar sign `
    --ks $KeystorePath `
    --ks-pass pass:android `
    --key-pass pass:android `
    --ks-key-alias androiddebugkey `
    --out $FinalApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$RootApk = "c:\Users\98733\AntigravityWork\BiliFilterHelper-v1.0.0.apk"
Copy-Item -Force $FinalApk $RootApk

Write-Host "✅ APK Successfully Built and Signed:"
Get-Item $FinalApk, $RootApk | Select-Object FullName, Length, LastWriteTime
