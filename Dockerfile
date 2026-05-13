# ใช้ Base Image เป็น Java 17 (หรือ 21 ตามที่คุณใช้)
FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

# Copy ไฟล์ JAR ที่ Build แล้วเข้าไป (เดี๋ยวเราค่อย Build)
# หมายเหตุ: ชื่อไฟล์ *.jar อาจเปลี่ยนตาม version ใน pom.xml
COPY target/*.jar app.jar

# เปิด Port 8080
EXPOSE 8080

# คำสั่งรัน
ENTRYPOINT ["java", "-jar", "app.jar"]