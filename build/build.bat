@echo off
chcp 65001 >nul
REM ============================================
REM Feishu Exporter 本地一键打包脚本（Windows）
REM 双击运行即可生成免安装 EXE
REM ============================================
setlocal

echo ============================================
echo  Feishu Exporter - Windows 本地打包
echo ============================================

cd /d "%~dp0.."

REM 1. 检查 Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Python，请先安装 Python 3.10+ 并加入 PATH
    pause
    exit /b 1
)

REM 2. 创建/激活虚拟环境（可选，保持干净）
if not exist ".venv" (
    echo [1/4] 创建虚拟环境...
    python -m venv .venv
)
call .venv\Scripts\activate.bat

REM 3. 安装依赖
echo [2/4] 安装依赖...
python -m pip install --upgrade pip -q
python -m pip install -r requirements.txt -q
python -m pip install pyinstaller -q

REM 4. 打包
echo [3/4] 打包 EXE（可能需要 1-3 分钟）...
cd build
python -m PyInstaller FeishuExporter.spec --clean --noconfirm
cd ..

REM 5. 收尾
echo [4/4] 整理产物...
if exist "build\dist\FeishuExporter.exe" (
    copy /Y "build\dist\FeishuExporter.exe" "FeishuExporter.exe" >nul
    echo.
    echo ============================================
    echo  打包成功！
    echo  产物：%~dp0..\FeishuExporter.exe
    echo  双击即可运行，无需安装 Python
    echo ============================================
) else (
    echo [错误] 未找到生成的 EXE，请查看上方日志
)

pause
endlocal
