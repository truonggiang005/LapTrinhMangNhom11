# Script khoi tao 4 CSDL cho 4 Server tren MySQL cuc bo
param (
    [string]$MySqlPath = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$User = "root",
    [string]$Password = ""
)

if (-not (Test-Path $MySqlPath)) {
    Write-Warning "Khong tim thay mysql.exe tai $MySqlPath. Vui long kiem tra lai duong dan."
    return
}

$passArg = if ($Password -ne "") { "-p$Password" } else { "-p" }

Write-Host "Dang tao 4 database (exam_db_1 .. exam_db_4)..."
& $MySqlPath -u $User $passArg -e "CREATE DATABASE IF NOT EXISTS exam_db_1 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS exam_db_2 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS exam_db_3 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS exam_db_4 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

foreach ($i in 1..4) {
    $dbName = "exam_db_$i"
    Write-Host "Dang nap luoc do va du lieu mau vao $dbName..."
    Get-Content "db\schema.sql" | & $MySqlPath -u $User $passArg $dbName
    Get-Content "db\sample_data.sql" | & $MySqlPath -u $User $passArg $dbName
}

Write-Host "Hoan tat khoi tao 4 database thanh cong!" -ForegroundColor Green
