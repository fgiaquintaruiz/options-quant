@echo off
REM Atajo en la raíz del repo: levanta Java + Python analytics + Streamlit.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-full-stack.ps1" %*
