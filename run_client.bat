@echo off
chcp 65001 > nul
title Hệ Thống Thi Trắc Nghiệm Trực Tuyến Đa Máy Chủ
echo ==============================================================
echo Đang mở Giao diện Ứng dụng Client (Swing UI)...
echo ==============================================================
java -jar client-ui/target/client-ui-1.0.0.jar
pause
