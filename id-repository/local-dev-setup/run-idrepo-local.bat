@echo off
REM Rebuild id-repository-service and run it on the host against local Docker deps.
REM Prerequisites:
REM   Deps up:  deps\restart-idrepo.bat   (or run-local-stack.sh up)
REM
REM Usage (from local-dev-setup):
REM   run-idrepo-local.bat              rebuild jar, then start
REM   run-idrepo-local.bat --no-build   start existing jar only
REM   run-idrepo-local.bat --build      same as default (kept for compatibility)

setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "MAVEN_PARENT=%~dp0.."
set "SERVICE_DIR=%MAVEN_PARENT%\id-repository-service"
set "TARGET_DIR=%SERVICE_DIR%\target"
set "CONFIG_URI=http://localhost:51100/config"
set "JAVA_OPTS=-Xms512m -Xmx1536m"
set "DO_BUILD=1"

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--no-build" (
  set "DO_BUILD=0"
  shift
  goto parse_args
)
if /i "%~1"=="--build" (
  set "DO_BUILD=1"
  shift
  goto parse_args
)
echo ERROR: unknown argument: %~1
echo Usage: run-idrepo-local.bat [--build^|--no-build]
exit /b 1
:args_done

if "%DO_BUILD%"=="1" (
  echo Building id-repository-service...
  pushd "%MAVEN_PARENT%"
  REM Full package lifecycle so git-commit-id (validate) + spring-boot:build-info run;
  REM cherry-picked compile/jar/repackage skips META-INF/build-info.properties and git.properties.
  call mvn -pl id-repository-service clean package -Dmaven.test.skip=true -Dgpg.skip=true -Dmaven.javadoc.skip=true
  if errorlevel 1 (
    echo ERROR: Maven build failed.
    popd
    exit /b 1
  )
  popd
)

set "JAR="
for %%F in ("%TARGET_DIR%\id-repository-service-*.jar") do (
  echo %%~nxF | findstr /i /r "sources javadoc tests" >nul
  if errorlevel 1 set "JAR=%%~fF"
)
if not defined JAR (
  echo ERROR: id-repository-service jar not found under:
  echo   %TARGET_DIR%
  echo Run without --no-build, or:
  echo   cd id-repository ^&^& mvn -pl id-repository-service -am package -DskipTests -Dgpg.skip=true
  exit /b 1
)

echo Waiting for config-server at %CONFIG_URI% ...
set /a _i=0
:wait_cfg
curl -sf "%CONFIG_URI%/id-repository/default" >nul 2>&1
if not errorlevel 1 goto cfg_ok
set /a _i+=1
if !_i! geq 60 (
  echo ERROR: config-server not reachable. Start deps first:
  echo   deps\restart-idrepo.bat
  echo   or: bash run-local-stack.sh up
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto wait_cfg
:cfg_ok
echo config-server is ready.

echo.
echo Starting id-repository-service on http://localhost:8090
echo Jar: %JAR%
echo Stop with Ctrl+C
echo.

java %JAVA_OPTS% ^
  -Dfile.encoding=UTF-8 ^
  --add-opens java.base/java.lang=ALL-UNNAMED ^
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
  --add-opens java.base/java.io=ALL-UNNAMED ^
  -Dspring.cloud.config.uri=%CONFIG_URI% ^
  -Dspring.config.import=configserver:%CONFIG_URI% ^
  -Dspring.cloud.config.label=develop ^
  -Dspring.profiles.active=default,local ^
  -Dspring.cloud.config.override-none=true ^
  -Dspring.cloud.loadbalancer.enabled=false ^
  -Dserver.port=8090 ^
  -jar "%JAR%"

endlocal
exit /b %ERRORLEVEL%
