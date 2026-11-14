@echo off
chcp 65001 > nul
title Hệ Thống Thi Trực Tuyến - Máy Chủ 1 & Bộ Điều Phối
echo ==============================================================
echo Đang khởi động Máy Chủ 1 (Cổng 9001) & Bộ Điều Phối (Cổng 9000)...
echo ==============================================================
java -jar server/target/server-1.0.0.jar 1
pause
