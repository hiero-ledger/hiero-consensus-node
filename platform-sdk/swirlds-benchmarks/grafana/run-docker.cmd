set SCRIPT_DIR=%~dp0
cd /d %SCRIPT_DIR%
set SCRIPT_DIR=%cd%

docker run ^
    --name grafana ^
    -d ^
    --rm ^
    -p 3000:3000 ^
    -v "%SCRIPT_DIR%\..\data:/benchmark-data" ^
    -v "%SCRIPT_DIR%:/benchmark-grafana" ^
    -e GF_PATHS_CONFIG=/benchmark-grafana/grafana.ini ^
    -e GF_PATHS_DATA=/benchmark-grafana/data ^
    -e GF_PATHS_PLUGINS=/benchmark-grafana/plugins ^
    -e GF_PATHS_PROVISIONING=/benchmark-grafana/provisioning ^
    -e GF_INSTALL_PLUGINS=marcusolsson-csv-datasource ^
    grafana/grafana-oss