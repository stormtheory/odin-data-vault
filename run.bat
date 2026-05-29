@echo off
setlocal enabledelayedexpansion
:: ####################################################################
:: ##
:: ##              WINDOWS (7 / 10 / 11 / Server 2008+)
:: ##             Script is to make building, launching,
:: ##      and running easier with command line (CLI) arguments
:: ##
:: ##               With Love, Stormtheory
:: ##
:: ####################################################################
:: https://central.sonatype.com/artifact/de.mkammerer/argon2-jvm/versions
  :: https://repo1.maven.org/maven2/de/mkammerer/argon2-jvm/
  :: https://repo1.maven.org/maven2/de/mkammerer/argon2-jvm-nolibs/
  :: https://repo1.maven.org/maven2/net/java/dev/jna/jna/
:: https://github.com/xerial/sqlite-jdbc/releases
:: https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.7/

:: ── Dependency filenames (update versions here only) ─────────────────
set ARGON2_LIB=argon2-jvm-2.12.jar
set ARGON2_NOLIB=argon2-jvm-nolibs-2.12.jar
set JNA_LIB=jna-5.18.1.jar
set BOUNCY_HOUSE_LIB=bcprov-jdk18on-1.84.jar
set SQLITE_LIB=sqlite-jdbc-3.53.0.0.jar
set PDF_LIB=pdfbox-app-3.0.7.jar
set JSON_LIB=json-20251224.jar

:: Jar output file
set JAR_FILENAME=OdinDataVault.jar


set "PROJECT_NAME=odin-data-vault"

:: ZIP goes into the parent folder, named after the project
set "ZIP_FILE=odin-data-vault.zip"
set "ZIP_PATH=%PARENT_DIR%%ZIP_FILE%"

:: Stage into a temp copy that excludes .git (mirrors tar --exclude=.git)
set "STAGE=%TEMP%\odin-data-vault-stage"

:: ── Minimum required Java version ────────────────────────────────────
:: Argon2-jvm requires at least Java 11 (JNA bridge).
:: --enable-native-access was introduced in Java 17; we detect the
:: version at runtime and add the flag only when supported.
set JAVA_MIN=11

:: ── Change to the directory containing this script ───────────────────
:: Equivalent to bash's: cd "$(dirname "$0")"
:: This ensures bin\, lib\, and *.java are found correctly regardless of
:: where the script is launched from (Desktop shortcut, Explorer, CLI).
cd /d "%~dp0"

:: ── Detect double-click vs CLI launch ────────────────────────────────
:: SESSIONNAME is set by cmd.exe when launched from an existing terminal
:: session and absent when Explorer spawns a fresh console window.
:: This avoids the CMDCMDLINE approach which breaks when Explorer appends
:: a stray quote to arguments, e.g.: "run.bat" -h"
set DOUBLE_CLICKED=false
if not defined SESSIONNAME set DOUBLE_CLICKED=true

:: ── Safety: refuse to run as Administrator ───────────────────────────
:: net session succeeds only when the process has true elevation (admin
:: token). More reliable than matching SID S-1-16-12288 via whoami /groups
:: which false-positives on domain machines or certain UAC configurations.
::
:: Security rationale: a password vault should never run as admin --
:: doing so widens the blast radius of any exploit or misconfiguration.
net session >nul 2>&1
if %errorlevel% == 0 (
    echo [SECURITY] This script must NOT be run as Administrator.
    echo            Please re-run as a normal ^(non-elevated^) user.
    call :error_exit
)

:: ── Java presence and version check ──────────────────────────────────
:: Fail fast with a clear message rather than a cryptic JVM error.
:: We parse the major version out of "java -version" stderr output.
:: java -version always prints to stderr, so we redirect 2>&1.
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] java not found on PATH. Please install Java %JAVA_MIN%+ and retry.
    call :error_exit
)

:: Extract the major version number into JAVA_VER.
:: "java -version" prints e.g.:  openjdk version "17.0.2" ...
:: or legacy:                     java version "1.8.0_361" ...
:: We grab the quoted version token, strip quotes, then take the part
:: before the first dot. For 1.x releases the major is the second token.
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VER_RAW=%%~V"
)
:: Strip leading "1." for Java 8 and earlier (1.8 -> 8)
set "JAVA_VER=%JAVA_VER_RAW%"
if "%JAVA_VER_RAW:~0,2%"=="1." set "JAVA_VER=%JAVA_VER_RAW:~2%"
:: Keep only the major number (everything before the first dot)
for /f "delims=." %%M in ("%JAVA_VER%") do set "JAVA_MAJOR=%%M"

:: Numeric comparison - reject if below minimum
if %JAVA_MAJOR% LSS %JAVA_MIN% (
    echo [ERROR] Java %JAVA_MAJOR% detected. This application requires Java %JAVA_MIN% or newer.
    echo         Please upgrade your JDK: https://www.oracle.com/java/technologies/downloads/
    call :error_exit
)

:: --enable-native-access=ALL-UNNAMED is required by Argon2's JNA bridge
:: but the flag itself only exists in Java 17+. On Java 11-16 JNA works
:: without it (the module system is less strict). We set the flag
:: conditionally so the script runs on both old and new JVMs.
set "NATIVE_ACCESS_FLAG="
if %JAVA_MAJOR% GEQ 17 set "NATIVE_ACCESS_FLAG=--enable-native-access=ALL-UNNAMED"

echo [Java] Detected version %JAVA_MAJOR% - native-access flag: %NATIVE_ACCESS_FLAG%

:: ── Default flag values ──────────────────────────────────────────────
:: All flags start false; parsed flags flip them to true.
:: HELP starts true so that running with no args triggers the
:: smart auto-build/run behaviour (see dispatch section below).
set DOWNLOADS=false
set DO_BUILD=false
set DO_RUN=false
set DO_TAR=false
set DO_JAR=false
set DO_JAR_RELEASE=false
set DO_HELP=false
:: HELP=true means "no recognised flags were given" - triggers auto-build/run.
:: It is flipped to false by every flag handler including -h (via DO_HELP).
set HELP=true

:: ── Jump past subroutine definitions to the argument parser ──────────
:: Batch falls through top-to-bottom; without this goto the interpreter
:: would execute subroutine bodies as main-line code on startup.
goto :parse_args

:: ====================================================================
:: FUNCTION: error_exit
::   Centralised error exit. When the script was double-clicked from
::   Explorer, pauses before closing so the user can read the message.
::
::   IMPORTANT: uses "exit 1" (no /b) to terminate the entire cmd.exe
::   process, not just the current subroutine call frame. "exit /b 1"
::   only unwinds one CALL level - the caller keeps running, which is
::   why errors were being ignored. The tradeoff is that this closes
::   the console window when run from an existing terminal; acceptable
::   for a build script where an error should always be fatal.
:: ====================================================================
:error_exit
echo.
echo [ERROR] Script aborted. See message above for details.
if "%DOUBLE_CLICKED%"=="true" (
    echo.
    pause
)
exit 1

:: ====================================================================
:: FUNCTION: show_help
::   Prints usage information (mirrors the Linux EOF heredoc block).
:: ====================================================================
:show_help
echo.
echo Usage: %~nx0 [OPTIONS]
echo.
echo   (no args)      First run: auto-builds then launches the program.
echo                  Subsequent runs: skips build and launches directly.
echo                  Use -b or -i to force a rebuild at any time.
echo Options:
echo   -d             Copy the zip to the Downloads directory
echo   -i             Force rebuild
echo   -b             Force rebuild
echo   -r             Run only (skips build even if bin\ is empty)
echo   -j             Create fat Jar file with current primary system JDK
echo   -R             Create fat Jar file for JDK 17,21,25 for release
echo   -h             Show this help message
echo.
echo Examples:
echo   %~nx0           -- smart default: build once, then just run
echo   %~nx0 -b        -- force rebuild only
echo   %~nx0 -br       -- force rebuild then run
echo   %~nx0 -r        -- run only (no build check)
echo.
goto :eof

:: ====================================================================
:: FUNCTION: parse_args
::   Loops through all CLI tokens. Tokens starting with '-' are handed
::   to :parse_flags for character-by-character processing.
::   Unknown tokens print help and exit cleanly.
:: ====================================================================
:parse_args
if "%~1"=="" goto end_parse

set "arg=%~1"
:: Only process tokens that begin with a dash.
:: IMPORTANT: do NOT call :parse_flags from inside an if ( ) block -
:: variables set inside a parenthesised block are expanded at parse time,
:: not execution time, so %flags% would always pass the stale (empty)
:: value. We use a goto to branch instead, keeping the call at top level.
if "%arg:~0,1%"=="-" goto :do_parse_flags
echo [ERROR] Unknown argument: %arg% >&2
call :show_help
call :error_exit

:do_parse_flags
:: Strip the leading dash and pass the rest to parse_flags
set "flags=%arg:~1%"
call :parse_flags "%flags%"
shift
goto parse_args
:end_parse

:: ── Dispatch based on parsed flags ──────────────────────────────────
:: -h is checked first so it always wins, even if combined with other flags
if "%DO_HELP%"=="true" (
    call :show_help
    exit /b 0
)

if "%DO_JAR%"=="true" (
    call :JAR
    exit /b 0
)

if "%DO_JAR_RELEASE%"=="true" (
    call :JAR_RELEASE
    exit /b 0
)

if "%DO_BUILD%"=="true" call :BUILD

if "%DO_TAR%"=="true" call :ZIP_UP

:: ── Default behaviour when no flags were passed ──────────────────────
:: Goal: the user should always be able to just run this script and
:: have things work. The build should happen exactly once - automatically
:: on first run - and be skipped on every subsequent run unless the
:: user explicitly requests a rebuild with -b or -i.
::
:: Logic:
::   bin\*.class exists  ->  skip build, launch immediately
::   bin\*.class missing ->  first-time setup: build then launch
::
:: To force a rebuild at any time, pass -b or -br explicitly.
if "%HELP%"=="true" (
    if exist bin\*.class (
        :: Already built - just launch
        echo [Auto] Classes found -- launching program...
        call :RUN
    ) else (
        :: First run: no classes yet - build once, then launch
        echo [Auto] First run -- building before launch...
        call :BUILD
        echo [Auto] Build complete -- launching program...
        call :RUN
    )
    exit /b 0
)

if "%DO_RUN%"=="true" call :RUN

exit /b 0

:: ====================================================================
:: FUNCTION: parse_flags
::   Iterates each character in the passed flag string and sets booleans.
::   Called once per CLI token (e.g. "-br" sets BUILD and RUN).
:: ====================================================================
:parse_flags
set "str=%~1"
:flag_loop
if "%str%"=="" goto :eof
set "char=%str:~0,1%"
set "str=%str:~1%"

if "%char%"=="d" (
    set DO_TAR=true
    set DOWNLOADS=true
    set HELP=false
)
if "%char%"=="i" (
    set DO_BUILD=true
    set HELP=false
)
if "%char%"=="b" (
    set DO_BUILD=true
    set HELP=false
)
if "%char%"=="r" (
    set DO_RUN=true
    set HELP=false
)
if "%char%"=="j" (
    set DO_JAR=true
    set HELP=false
)
if "%char%"=="R" (
    set DO_JAR_RELEASE=true
    set HELP=false
)
if "%char%"=="h" (
    :: Set DO_HELP and clear HELP so the dispatch section handles this
    :: cleanly at the top level - never try to goto or exit /b from
    :: inside a nested call subroutine, it doesn't unwind reliably.
    set DO_HELP=true
    set HELP=false
)
goto flag_loop

:: ====================================================================
:: FUNCTION: BUILD
::   Cleans the bin directory and recompiles all .java sources.
::   Classpath uses semicolons on Windows (colons on Linux/Mac).
:: ====================================================================
:BUILD
:: Clean old class files before recompile
mkdir "bin" 2>nul
if exist bin\* del /q bin\*

:: -encoding UTF-8 is required on Windows because javac defaults to the
:: system codepage (usually windows-1252) which cannot represent Unicode
:: characters used in string literals and comments in the source files.
:: Without this flag, any non-ASCII character (emoji, bullet, en-dash etc.)
:: causes "unmappable character" errors and a broken build.
echo javac -encoding UTF-8 -cp ".;lib\%SQLITE_LIB%;lib\%PDF_LIB%;lib\%JSON_LIB%;lib\%ARGON2_LIB%;lib\%ARGON2_NOLIB%;lib\%BOUNCY_HOUSE_LIB%;lib\%JNA_LIB%;bin" -d bin java\*.java
javac -encoding UTF-8 -cp ".;lib\%SQLITE_LIB%;lib\%PDF_LIB%;lib\%JSON_LIB%;lib\%ARGON2_LIB%;lib\%ARGON2_NOLIB%;lib\%BOUNCY_HOUSE_LIB%;lib\%JNA_LIB%;bin" -d bin java\*.java

:: Abort immediately if javac failed - do not attempt to launch with
:: missing or stale class files, which produces a misleading
:: "ClassNotFoundException" instead of the real compile errors above.
if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed -- see errors above. Launch aborted.
    call :error_exit
)
goto :eof

:: ====================================================================
:: FUNCTION: RUN
::   Launches the compiled Odin. NATIVE_ACCESS_FLAG is set at startup:
::     Java 17+ -> --enable-native-access=ALL-UNNAMED (required by JNA)
::     Java 11-16 -> empty string (JNA works without it on older runtimes)
::   -Dorg.sqlite.tmpdir=. keeps SQLite temp files local (portable).
:: ====================================================================
:RUN
echo java %NATIVE_ACCESS_FLAG% -cp ".;lib\%SQLITE_LIB%;lib\%PDF_LIB%;lib\%JSON_LIB%;lib\%ARGON2_LIB%;lib\%ARGON2_NOLIB%;lib\%BOUNCY_HOUSE_LIB%;lib\%JNA_LIB%;bin" Odin
java %NATIVE_ACCESS_FLAG% -cp ".;lib\%SQLITE_LIB%;lib\%PDF_LIB%;lib\%JSON_LIB%;lib\%ARGON2_LIB%;lib\%ARGON2_NOLIB%;lib\%BOUNCY_HOUSE_LIB%;lib\%JNA_LIB%;bin" Odin
goto :eof



:: ====================================================================
:: FUNCTION: JAR_RELEASE
::   Builds a fat (uber) jar for EVERY JDK discovered on this machine.
::   One jar per JDK version, output: PROJECT_NAME-YYMMDD-{MAJOR}.jar
::   A matching .vbs windowless launcher is created alongside each jar.
::   Steps:
::     1. Date stamp (PowerShell -> wmic -> %DATE% fallback chain)
::     2. Discover all JDKs across all known vendor install roots
::     3. For each JDK: compile, stage, explode deps, strip sigs, package
::     4. Write a .vbs launcher per jar for double-click windowless launch
:: ====================================================================
:JAR_RELEASE

:: ====================================================================
:: DATE STAMP - pure cmd fallback chain, works Win 7 / Server 2008 / Win 11.
::   1. PowerShell Get-Date  - most reliable, locale-independent (PS 2.0+)
::   2. wmic LocalDateTime   - Win 7/10/Server, removed in Win 11
::   3. %DATE% strip+slice   - last resort, locale-fragile but universal
:: ====================================================================
set "DATESTAMP="

:: -- Try PowerShell first (works Win 7+ if PS is on PATH) --
for /f %%D in ('powershell -NoProfile -Command "Get-Date -Format yyMMdd" 2^>nul') do set "DATESTAMP=%%D"

:: -- Fallback to wmic (Win 7/10/Server 2008, not Win 11) --
if not defined DATESTAMP (
    for /f "tokens=2 delims==" %%D in ('wmic os get LocalDateTime /value 2^>nul') do set "_DT=%%D"
    if defined _DT set "DATESTAMP=!_DT:~2,6!"
)

:: -- Last resort: parse %DATE% env var directly --
::   Strip all separators then slice - fragile if locale uses unusual format
if not defined DATESTAMP (
    set "_D=%DATE:/=%"
    set "_D=!_D:-=!"
    set "_D=!_D: =!"
    set "DATESTAMP=!_D:~2,6!"
)

echo [Date] Stamp: %DATESTAMP%

:: ====================================================================
:: JDK DISCOVERY - scan all known vendor install roots for every jdk*
::   directory. Builds indexed variable tuples: JDK_N_VER, JDK_N_JAR,
::   JDK_N_JAVAC, JDK_N_DIR for use in the build loop below.
::   Covers: Oracle, Adoptium/Temurin, Microsoft, Corretto, BellSoft.
:: ====================================================================
set "JDK_COUNT=0"

for %%R in (
    "C:\Program Files\Java"
    "C:\Program Files\Eclipse Adoptium"
    "C:\Program Files\Microsoft"
    "C:\Program Files\Amazon Corretto"
    "C:\Program Files\BellSoft"
) do (
    if exist "%%~R\" (
        :: Loop every jdk* subfolder under this vendor root
        for /d %%D in ("%%~R\jdk*") do (
            :: Only count dirs that have both jar.exe and javac.exe - JREs don't
            if exist "%%~D\bin\jar.exe" if exist "%%~D\bin\javac.exe" (

                :: Extract the major version from the folder name.
                ::   Folder names vary: jdk-17.0.11, jdk-21, jdk17, jdk1.8.0_xxx
                ::   Strip "jdk-" or "jdk" prefix, then take the leading number.
                set "_RAW=%%~nxD"
                :: Remove common prefixes so we are left with the version digits
                set "_RAW=!_RAW:jdk-=!"
                set "_RAW=!_RAW:jdk=!"
                :: Take only the major version (everything before first dot/dash/underscore)
                for /f "delims=.-_" %%V in ("!_RAW!") do set "_MAJOR=%%V"

                :: Handle legacy 1.x versioning (jdk1.8 -> major 8)
                if "!_MAJOR!"=="1" (
                    :: Re-parse: skip the "1." and grab the next token
                    for /f "tokens=2 delims=." %%M in ("!_RAW!") do set "_MAJOR=%%M"
                )

                :: Store discovered JDK in indexed variables for the build loop
                set /a JDK_COUNT+=1
                set "JDK_!JDK_COUNT!_DIR=%%~D"
                set "JDK_!JDK_COUNT!_VER=!_MAJOR!"
                set "JDK_!JDK_COUNT!_JAR=%%~D\bin\jar.exe"
                set "JDK_!JDK_COUNT!_JAVAC=%%~D\bin\javac.exe"

                echo [Discovery] Found JDK !_MAJOR! at %%~D
            )
        )
    )
)

:: Abort early if no JDKs found - nothing to build
if %JDK_COUNT%==0 (
    echo [ERROR] No JDKs found in any known install location.
    echo         Install from https://www.oracle.com/java/technologies/downloads/ or set JAVA_HOME.
    call :error_exit
)
echo [Discovery] Total JDKs found: %JDK_COUNT%
echo.

:: ====================================================================
:: BUILD LOOP - calls :BUILD_ONE subroutine per discovered JDK.
::   Labels are ILLEGAL inside ( ) for /l blocks in cmd - any goto
::   inside a parenthesised loop breaks paren-balancing and causes the
::   ") was unexpected" error. The :BUILD_ONE subroutine pattern is the
::   only safe way to use goto (skip/error handling) inside a loop.
:: ====================================================================
set "_BUILD_ERRORS=0"
for /l %%I in (1,1,%JDK_COUNT%) do (
    call :BUILD_ONE %%I
)

:: -- Final summary - show OK/FAIL per jar and matching launcher --
echo.
echo #### All builds complete ####
if %_BUILD_ERRORS% GTR 0 echo [WARN] %_BUILD_ERRORS% build^(s^) failed - check output above.
echo.
echo Jars produced:
for /l %%I in (1,1,%JDK_COUNT%) do (
    set "_V=!JDK_%%I_VER!"
    if exist "%PROJECT_NAME%-%DATESTAMP%-!_V!.jar" (
        echo   [OK]   %PROJECT_NAME%-%DATESTAMP%-!_V!.jar
        echo   [OK]   %PROJECT_NAME%-%DATESTAMP%-!_V!.vbs
    ) else (
        echo   [FAIL] JDK !_V! - jar not produced
    )
)
goto :eof

:: ====================================================================
:: FUNCTION: BUILD_ONE %1=index
::   Compiles and packages one fat jar for the JDK at discovery index N.
::   Isolated as a subroutine so goto :eof safely skips a failed build
::   without corrupting the parent for /l loop's parenthesis balance.
::   On compile failure: increments _BUILD_ERRORS and returns cleanly.
:: ====================================================================
:BUILD_ONE
set "_IDX=%1"
set "_VER=!JDK_%_IDX%_VER!"
set "_JAVAC=!JDK_%_IDX%_JAVAC!"
set "_JAR=!JDK_%_IDX%_JAR!"
set "_OUTJAR=%PROJECT_NAME%-%DATESTAMP%-!_VER!.jar"
set "_STAGE=fatjar_!_VER!"

echo ============================================================
echo [Build] JDK !_VER! -^> !_OUTJAR!/.vbs
echo ============================================================

:: -- Clean previous artifacts for this version --
echo [1/8] Cleaning build artifacts for JDK !_VER!...
if exist "bin_!_VER!" rd /s /q "bin_!_VER!"
if exist "!_OUTJAR!" del /q "!_OUTJAR!"
if exist "!_STAGE!" rmdir /s /q "!_STAGE!"

:: -- Compile all .java sources with this JDK into a version-specific bin dir --
::   -encoding UTF-8 prevents unmappable character errors on Windows codepages
echo [2/8] Compiling with javac !_VER!...
mkdir "bin_!_VER!" 2>nul
"!_JAVAC!" -encoding UTF-8 -cp ".;lib\%SQLITE_LIB%;lib\%PDF_LIB%;lib\%JSON_LIB%;lib\%ARGON2_LIB%;lib\%ARGON2_NOLIB%;lib\%BOUNCY_HOUSE_LIB%;lib\%JNA_LIB%" -d "bin_!_VER!" java\*.java
if errorlevel 1 (
    echo [WARN] Compile failed for JDK !_VER! - skipping this version.
    set /a _BUILD_ERRORS+=1
    goto :eof
)

:: -- Stage compiled classes into isolated fat jar directory --
::   Each version gets its own _STAGE dir so builds never cross-contaminate
echo [3/8] Staging classes...
mkdir "!_STAGE!"
xcopy /e /i "bin_!_VER!" "!_STAGE!" >nul
mkdir "!_STAGE!\icons"
mkdir "!_STAGE!\icons\shield"
xcopy /i icons\shield\*.png "!_STAGE!\icons\shield\" >nul
xcopy /i README.md "!_STAGE!" >nul
xcopy /i LICENSE "!_STAGE!" >nul

:: -- Explode dependency jars into staging dir --
::   jar xf extracts all entries from each dep into the current directory
echo [4/8] Exploding dependencies...
cd "!_STAGE!"
"!_JAR!" xf "..\lib\%SQLITE_LIB%"
"!_JAR!" xf "..\lib\%ARGON2_LIB%"
"!_JAR!" xf "..\lib\%ARGON2_NOLIB%"
"!_JAR!" xf "..\lib\%JNA_LIB%"
"!_JAR!" xf "..\lib\%BOUNCY_HOUSE_LIB%"
"!_JAR!" xf "..\lib\%PDF_LIB%"
"!_JAR!" xf "..\lib\%JSON_LIB%"
cd ..

:: -- Strip BouncyCastle signature files to prevent JAR verification failure --
::   BouncyCastle ships signed; its .SF/.RSA/.DSA files invalidate the fat jar
::   because the merged classes no longer match the original signature hashes.
echo [5/8] Stripping signature files...
if exist "!_STAGE!\META-INF\*.SF"  del /q "!_STAGE!\META-INF\*.SF"
if exist "!_STAGE!\META-INF\*.RSA" del /q "!_STAGE!\META-INF\*.RSA"
if exist "!_STAGE!\META-INF\*.DSA" del /q "!_STAGE!\META-INF\*.DSA"

:: -- Write manifest - trailing blank line after Main-Class is required by JAR spec --
echo [6/8] Writing manifest...
if not exist "!_STAGE!\META-INF" mkdir "!_STAGE!\META-INF"
(
    echo Manifest-Version: 1.0
    echo Main-Class: Odin
    echo.
) > "!_STAGE!\META-INF\MANIFEST.MF"

:: -- Package everything into the final fat jar - slowest step --
echo [7/8] .jar Packaging !_OUTJAR!...
cd "!_STAGE!"
"!_JAR!" cfm "..\!_OUTJAR!" META-INF\MANIFEST.MF .
cd ..

:: -- Generate a windowless .vbs launcher alongside this jar --
echo [8/8] .vbs Packaging
call :MAKE_RELEASE_LAUNCHER "!_OUTJAR!"

echo [Done] !_OUTJAR! created.
echo.
goto :eof

:: ====================================================================
:: FUNCTION: MAKE_RELEASE_LAUNCHER %1=jar filename
::   Writes a .vbs launcher alongside each release jar so users can
::   double-click to launch with no console window flashing up.
::   Uses javaw (windowless java) with NATIVE_ACCESS_FLAG baked in.
::   The JDK version is embedded in the .vbs filename to match the jar.
::   Safe to re-run - overwrites any existing launcher for that version.
:: ====================================================================
:MAKE_RELEASE_LAUNCHER
set "_LJ=%~1"
:: Derive the .vbs filename by swapping .jar extension for .vbs
set "_VBS=%_LJ:.jar=.vbs%"

:: -- Remove stale launcher if present --
if exist "%_VBS%" del /q "%_VBS%"

:: Write each VBScript line with >> append redirection individually.
:: A parenthesised ( echo ... ) block mis-parses closing parens inside
:: VBScript expressions (e.g. InStrRev) even when escaped, because cmd
:: balances parens before processing escape chars. Line-by-line >> is safe.
echo ' %_LJ% - windowless launcher                                    >> "%_VBS%"
echo ' Double-click to launch the app with no console window.         >> "%_VBS%"
echo ' Requires Java (javaw.exe) to be on the system PATH.            >> "%_VBS%"
echo Set sh = CreateObject("WScript.Shell")                           >> "%_VBS%"
echo ' Resolve the directory this .vbs file lives in                  >> "%_VBS%"
echo scriptDir = Left(WScript.ScriptFullName, InStrRev(WScript.ScriptFullName, "\"))>> "%_VBS%"
echo jarPath = scriptDir ^& "%_LJ%"                                  >> "%_VBS%"
echo ' Build the javaw command with native access flag if Java 17+    >> "%_VBS%"
echo ' NATIVE_ACCESS_FLAG is empty on Java 11-16, populated on 17+    >> "%_VBS%"
echo cmd = "javaw %NATIVE_ACCESS_FLAG% -jar """ ^& jarPath ^& """">> "%_VBS%"
echo ' WindowStyle 0 = hidden, bWaitOnReturn False = fire and forget  >> "%_VBS%"
echo sh.Run cmd, 0, False                                             >> "%_VBS%"

echo [Launcher] Created: %_VBS%
goto :eof


:: ====================================================================
:: FUNCTION: JAR
::   Builds a fat (uber) jar containing all dependency classes so the
::   app can be distributed as a single executable .jar file.
::   Steps:
::     1. Clean old artifacts
::     2. Compile sources (calls BUILD)
::     3. Explode dependency jars into a staging directory
::     4. Write a manifest pointing at the Odin entry class
::     5. Re-package everything into the final fat jar
:: ====================================================================
:JAR
:: Resolve jar.exe from the same JDK that provides java.exe.
:: Modern Windows installs have TWO java stubs:
::   1. A Windows Store app-execution alias in %LOCALAPPDATA%\Microsoft\WindowsApps
::      - this is a shim that launches the Store; jar.exe is NOT beside it.
::   2. The real JDK bin directory added by the installer.
:: "where java" returns ALL matches in PATH order; we skip the shim by
:: checking each candidate directory for jar.exe beside it.
::
:: Resolution order:
::   1. JAVA_HOME\bin\jar.exe          - explicit env var, most reliable
::   2. Sibling of java.exe on PATH    - skips WindowsApps shims via where
::   3. C:\Program Files\Java\jdk*     - Oracle JDK default
::   4. C:\Program Files\Eclipse Adoptium\jdk*  - Temurin default
::   5. C:\Program Files\Microsoft\jdk*         - Microsoft JDK default
::   6. C:\Program Files\Amazon Corretto\jdk*   - Corretto default
::   7. C:\Program Files\BellSoft\jdk*          - Liberica default
set "JAR_EXE="

:: Try JAVA_HOME first
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jar.exe" set "JAR_EXE=%JAVA_HOME%\bin\jar.exe"
)

:: Walk every "java" hit on PATH; take the first one that has jar.exe beside it.
:: This skips WindowsApps shims which never have jar.exe next to them.
if not defined JAR_EXE (
    for /f "delims=" %%I in ('where java 2^>nul') do (
        if not defined JAR_EXE (
            if exist "%%~dpIjar.exe" set "JAR_EXE=%%~dpIjar.exe"
        )
    )
)

:: Last resort: probe well-known JDK install locations on Windows.
:: Covers Oracle, Adoptium/Temurin, Microsoft, Amazon Corretto, BellSoft.
:: Each root is guarded with if exist so missing directories are skipped
:: cleanly. The jdk* wildcard catches any major/patch version installed.
if not defined JAR_EXE (
    for %%R in (
        "C:\Program Files\Java"
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Microsoft"
        "C:\Program Files\Amazon Corretto"
        "C:\Program Files\BellSoft"
    ) do (
        if not defined JAR_EXE (
            if exist "%%~R\" (
                for /d %%D in ("%%~R\jdk*") do (
                    if not defined JAR_EXE (
                        if exist "%%~D\bin\jar.exe" set "JAR_EXE=%%~D\bin\jar.exe"
                    )
                )
            )
        )
    )
)

if not defined JAR_EXE (
    echo [ERROR] jar.exe not found. Tried PATH and common install locations.
    echo         Fix options:
    echo           1. Set JAVA_HOME to your JDK root ^(e.g. C:\Program Files\Java\jdk-17^)
    echo           2. Add your JDK bin to PATH ^(C:\Program Files\Java\jdk-17\bin^)
    echo           3. Install a full JDK from https://www.oracle.com/java/technologies/downloads/
    call :error_exit
)
echo [Jar] Using: %JAR_EXE%

:: Step 1 – clean old build artifacts
echo [1/8] Cleaning old build artifacts...
if exist bin\* del /q bin\*
if exist "%JAR_FILENAME%" del /q "%JAR_FILENAME%"
if exist fatjar rmdir /s /q fatjar

:: Step 2 – compile sources
echo [2/8] Compiling sources...
call :BUILD

:: Step 3 – explode dependency jars into staging directory
echo [3/8] Staging class files...
mkdir fatjar
:: xcopy /e /i copies class tree and needed files into fatjar
xcopy /e /i bin fatjar >nul
mkdir "fatjar\icons"
mkdir "fatjar\icons\shield"
xcopy /i icons\shield\*.png "fatjar\icons\shield\" >nul
xcopy /i README.md fatjar >nul
xcopy /i LICENSE fatjar >nul
:: Explode each dependency jar - these are the slow steps on large jars
echo [4/8] Exploding dependency jars ^(this may take a moment^)...
cd fatjar
echo       - %SQLITE_LIB%
"%JAR_EXE%" xf "..\lib\%SQLITE_LIB%"
echo       - %ARGON2_LIB%
"%JAR_EXE%" xf "..\lib\%ARGON2_LIB%"
echo       - %ARGON2_NOLIB%
"%JAR_EXE%" xf "..\lib\%ARGON2_NOLIB%"
echo       - %JNA_LIB%
"%JAR_EXE%" xf "..\lib\%JNA_LIB%"
echo       - %BOUNCY_HOUSE_LIB%
"%JAR_EXE%" xf "..\lib\%BOUNCY_HOUSE_LIB%"
"%JAR_EXE%" xf "..\lib\%PDF_LIB%"
"%JAR_EXE%" xf "..\lib\%JSON_LIB%"
cd ..

:: Step 4 – strip Bouncy Castle signature files to prevent JAR verification failure
echo [5/8] Stripping signature files ^(prevents BouncyCastle verification errors^)...
if exist fatjar\META-INF\*.SF del /q fatjar\META-INF\*.SF
if exist fatjar\META-INF\*.RSA del /q fatjar\META-INF\*.RSA
if exist fatjar\META-INF\*.DSA del /q fatjar\META-INF\*.DSA

:: Step 5 – ensure META-INF exists then write the manifest
::   mkdir is guarded because an exploded jar may have already created it
echo [6/8] Writing manifest...
if not exist fatjar\META-INF mkdir fatjar\META-INF
:: Trailing blank line after Main-Class is required by the jar spec
(
echo Manifest-Version: 1.0
echo Main-Class: Odin
echo.
) > fatjar\META-INF\MANIFEST.MF

:: Step 6 – package into final fat jar - slowest step, packs thousands of files
echo [7/8] Packaging fat jar ^(slowest step - packing all classes^)...
cd fatjar
"%JAR_EXE%" cfm "..\%JAR_FILENAME%" META-INF\MANIFEST.MF .
cd ..
echo Done -- %JAR_FILENAME% created.

:: Step 7 – generate .vbs launcher and fix file association
echo [8/8] Writing launcher and registering file association...
call :MAKE_LAUNCHER
call :FIX_JAR_ASSOC

echo.
echo #### All done ####
echo   Double-click launcher: %~dp0%JAR_FILENAME:.jar=.vbs%
echo   Or run directly:       java -jar %JAR_FILENAME%
goto :eof

:: ====================================================================
:: FUNCTION: MAKE_LAUNCHER
::   Writes a .vbs file alongside the jar that launches it via javaw
::   with no console window. Safe to re-run - overwrites each build.
::   Embeds NATIVE_ACCESS_FLAG so it honours the same Java version
::   detection that the .bat performs at startup.
:: ====================================================================
:MAKE_LAUNCHER
set "VBS_FILE=%JAR_FILENAME:.jar=.vbs%"
:: Write each VBScript line individually with >> append redirection.
:: A single parenthesised ( echo ... ) block mis-parses closing parens
:: in VBScript expressions like InStrRev(...) even when escaped, because
:: batch counts parens for block balancing before processing escape chars.
:: Individual echo+>> sidesteps this entirely.
if exist "%VBS_FILE%" del /q "%VBS_FILE%"
echo ' JavaPasswordVault - windowless launcher>> "%VBS_FILE%"
echo ' Double-click this file to start the app with no console window.>> "%VBS_FILE%"
echo ' Requires Java (javaw.exe) to be on the system PATH.>> "%VBS_FILE%"
echo Set sh = CreateObject("WScript.Shell")>> "%VBS_FILE%"
echo ' Resolve the directory this .vbs lives in>> "%VBS_FILE%"
echo scriptDir = Left(WScript.ScriptFullName, InStrRev(WScript.ScriptFullName, "\"))>> "%VBS_FILE%"
echo jarPath = scriptDir ^& "%JAR_FILENAME%">> "%VBS_FILE%"
echo ' Run javaw (no console) with native access flag if Java 17+>> "%VBS_FILE%"
echo cmd = "javaw %NATIVE_ACCESS_FLAG% -jar """ ^& jarPath ^& """">> "%VBS_FILE%"
echo ' WindowStyle 0 = hidden, bWaitOnReturn False = non-blocking>> "%VBS_FILE%"
echo sh.Run cmd, 0, False>> "%VBS_FILE%"
echo [Launcher] Created: %VBS_FILE%
goto :eof

:: ====================================================================
:: FUNCTION: FIX_JAR_ASSOC
::   Registers .jar files to open with javaw.exe system-wide.
::   Requires Administrator elevation - skips gracefully if not elevated.
::   Uses JAVA_HOME if set, otherwise searches PATH for javaw.exe.
:: ====================================================================
:FIX_JAR_ASSOC
:: Locate javaw.exe using the same shim-aware strategy as jar.exe above.
set "JAVAW_EXE="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW_EXE=%JAVA_HOME%\bin\javaw.exe"
)
if not defined JAVAW_EXE (
    for /f "delims=" %%I in ('where java 2^>nul') do (
        if not defined JAVAW_EXE (
            if exist "%%~dpIjavaw.exe" set "JAVAW_EXE=%%~dpIjavaw.exe"
        )
    )
)
if not defined JAVAW_EXE (
    for %%R in (
        "C:\Program Files\Java"
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Microsoft"
        "C:\Program Files\Amazon Corretto"
        "C:\Program Files\BellSoft"
    ) do (
        if not defined JAVAW_EXE (
            if exist "%%~R\" (
                for /d %%D in ("%%~R\jdk*") do (
                    if not defined JAVAW_EXE (
                        if exist "%%~D\bin\javaw.exe" set "JAVAW_EXE=%%~D\bin\javaw.exe"
                    )
                )
            )
        )
    )
)
if not defined JAVAW_EXE (
    echo [Assoc] javaw.exe not found -- skipping file association.
    goto :eof
)

:: Attempt to write the ftype/assoc entries (silently fails if not elevated)
:: NATIVE_ACCESS_FLAG is included so double-clicked jars also get the flag
ftype jarfile="%JAVAW_EXE%" %NATIVE_ACCESS_FLAG% -jar "%%1" %%* >nul 2>&1
assoc .jar=jarfile >nul 2>&1

:: Check if assoc actually took by reading it back
assoc .jar 2>nul | find "jarfile" >nul 2>&1
if %errorlevel% == 0 (
    echo [Assoc] .jar now opens with: %JAVAW_EXE%
) else (
    echo [Assoc] Could not set file association ^(not elevated^).
    echo         To fix: right-click this .bat, Run as Administrator, then -j again.
    echo         The .vbs launcher works regardless -- no admin needed.
)
goto :eof

:: ====================================================================
:: FUNCTION: ZIP_UP
::   On Windows we create a .zip archive instead of a .tgz because
::   PowerShell's Compress-Archive is available natively on Win 10/11.
::   The -d flag also copies the zip to the user's Downloads folder.
::
::   Note: unlike the Linux version, we do NOT rename the directory
::   because robocopy staging avoids the rename side-effect entirely.
::   The .git directory is excluded to keep the archive clean.
:: ====================================================================
:ZIP_UP
:: Use %~dp0 (the script's own directory, always with trailing backslash)
:: rather than %cd% to be immune to the working directory at call time.
:: Strip the trailing backslash for robocopy source path.
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

:: Derive parent directory. %%~dpI already includes a trailing backslash
:: so we use it directly in ZIP_PATH without stripping - stripping a root
:: drive path like "C:\" would produce "C:" which is invalid.
for %%I in ("%SCRIPT_DIR%") do set "PARENT_DIR=%%~dpI"

:: Remove stale archive if present
if exist "%ZIP_PATH%" del /q "%ZIP_PATH%"

if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\%PROJECT_NAME%"

:: robocopy: /e = recurse subdirs incl. empty, /xd = exclude .git dir
robocopy "%SCRIPT_DIR%" "%STAGE%\%PROJECT_NAME%" /e /xd ".git" >nul

:: Compress the staged copy using PowerShell (no third-party tools needed)
powershell -NoProfile -Command ^
    "Compress-Archive -Path '%STAGE%\%PROJECT_NAME%' -DestinationPath '%ZIP_PATH%' -Force"

:: Clean up staging directory
rmdir /s /q "%STAGE%"

echo Archive created: %ZIP_PATH%

:: Optionally copy to Downloads (mirrors the -d flag behaviour)
if "%DOWNLOADS%"=="true" (
    copy /y "%ZIP_PATH%" "%USERPROFILE%\Downloads\" >nul
    echo Copied to: %USERPROFILE%\Downloads\%PROJECT_NAME%.zip
)
goto :eof

:: ── Clean exit point reachable from any depth via goto ───────────────
:: exit /b inside a nested CALL subroutine only unwinds one level.
:: goto :end_of_script jumps the entire call stack here unconditionally,
:: which is the only reliable way to stop execution from inside a nested
:: subroutine (e.g. parse_flags called from parse_args) without falling
:: through into unintended code paths.
:end_of_script
exit /b 0