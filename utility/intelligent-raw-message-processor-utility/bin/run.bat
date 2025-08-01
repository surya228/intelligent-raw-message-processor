:: cd..
:: cd lib/
:: java -jar intelligent-raw-message-processor.jar > ..\out\log\intelligent-raw-message-processor.log 2>&1
:: echo JAR executed successfully. Please check logs in out/log folder.
:: pause


@echo off

cd..
cd lib/


:: Set log directory
set LOG_DIR=..\out\log\

:: Create log directory if it doesn't exist
if not exist %LOG_DIR% mkdir %LOG_DIR%

:: Get current date and time in format YYYYMMDD_HHMMSS
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format \"yyyyMMdd_HHmmss\""') do set TIMESTAMP=%%a

:: Log file name
set LOG_FILE=intelligent-raw-message-processor_%TIMESTAMP%.log



echo [INFO] Starting intelligent-raw-message-processor.jar...
echo [INFO] Timestamped log: %LOG_DIR%\%LOG_FILE%
echo [INFO] Please wait, processing...

:: Run JAR and save logs
java -jar intelligent-raw-message-processor.jar > %LOG_DIR%\%LOG_FILE% 2>&1
:: java -jar intelligent-raw-message-processor.jar

:: Show completion message
echo [INFO] Execution complete. Logs saved to: %LOG_DIR%\%LOG_FILE%

