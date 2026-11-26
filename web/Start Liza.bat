@echo off
rem Starts Liza web on this computer and opens it in your browser.
cd /d "%~dp0"
python -I get_player.py || goto :error
start "" http://localhost:8790
python -I server.py
pause
exit /b

:error
echo.
echo Could not download the EZVIZ web player. Check your internet connection and try again.
pause
