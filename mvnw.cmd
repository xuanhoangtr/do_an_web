@echo off
setlocal

set "MAVEN_PROJECTBASEDIR=%~dp0"
set "WRAPPER_DIR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar"
set "WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar"
set "WRAPPER_SHA256=4e2fbf6554bc8a4702cdfdd3bef464f423393d784ddbb037216320ce55d5e4e1"

if not exist "%WRAPPER_JAR%" (
    echo Downloading Maven Wrapper 3.3.4...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
    if errorlevel 1 (
        echo Failed to download Maven Wrapper. 1>&2
        exit /b 1
    )
)

where certutil.exe >nul 2>nul
if errorlevel 1 (
    echo certutil.exe is required to verify Maven Wrapper. 1>&2
    exit /b 1
)
set "ACTUAL_SHA256="
for /f "skip=1 tokens=* delims=" %%H in ('certutil.exe -hashfile "%WRAPPER_JAR%" SHA256 ^| findstr /V /C:"CertUtil"') do if not defined ACTUAL_SHA256 set "ACTUAL_SHA256=%%H"
set "ACTUAL_SHA256=%ACTUAL_SHA256: =%"
if not defined ACTUAL_SHA256 (
    echo Unable to calculate Maven Wrapper checksum. 1>&2
    exit /b 1
)
if /I not "%ACTUAL_SHA256%"=="%WRAPPER_SHA256%" (
    echo Maven Wrapper checksum verification failed. 1>&2
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo JAVA_HOME or java must point to JDK 21 or newer. 1>&2
    exit /b 1
)

java %MAVEN_OPTS% %WRAPPER_JAVA_OPTS% -Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR% -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
