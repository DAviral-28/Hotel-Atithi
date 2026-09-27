@REM ----------------------------------------------------------------------------
@REM Maven Wrapper Batch Script (Self-Bootstrapping)
@REM ----------------------------------------------------------------------------
@IF "%DEBUG%" == "" @ECHO OFF
@SETLOCAL

SET ERROR_CODE=0

SET "MAVEN_PROJECTBASEDIR=%~dp0"
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

SET "MAVEN_VERSION=3.9.9"
SET "WRAPPER_DIR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper"
SET "MAVEN_HOME=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_EXE=%MAVEN_HOME%\bin\mvn.cmd"

IF EXIST "%MAVEN_EXE%" GOTO RunMaven

ECHO [mvnw] Apache Maven %MAVEN_VERSION% not found locally. Downloading...
SET "MAVEN_ZIP=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip"
SET "MAVEN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; " ^
    "Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_ZIP%';" ^
    "Expand-Archive -Path '%MAVEN_ZIP%' -DestinationPath '%WRAPPER_DIR%' -Force;" ^
    "Remove-Item -Path '%MAVEN_ZIP%' -Force"

IF NOT EXIST "%MAVEN_EXE%" (
    ECHO [mvnw] Failed to download or extract Maven %MAVEN_VERSION%.
    EXIT /B 1
)
ECHO [mvnw] Successfully installed Apache Maven %MAVEN_VERSION%.

:RunMaven
call "%MAVEN_EXE%" %*
IF ERRORLEVEL 1 GOTO Error
GOTO End

:Error
SET ERROR_CODE=1

:End
@ENDLOCAL & EXIT /B %ERROR_CODE%
