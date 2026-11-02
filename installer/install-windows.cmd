@echo off
rem Garmin Coach installer for Windows: double-click this file.
rem It runs the latest install.ps1 from GitHub (or, inside the repository, the one next to it).
title Garmin Coach installer
if exist "%~dp0..\build.clj" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/eliashauksson/garmin-claude-coach/main/installer/install.ps1 | iex"
)
echo.
pause
