-- Crea el usuario y la base de datos para desarrollo local.
-- Ejecutar UNA vez como superusuario:
--   & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -f db\crear-bd-local.sql
-- Las tablas NO se crean aquí: las crea Flyway al arrancar la API.

CREATE ROLE quiniela LOGIN PASSWORD 'quiniela_dev';
CREATE DATABASE quiniela OWNER quiniela ENCODING 'UTF8' TEMPLATE template0;

-- Base aparte para las pruebas automáticas (mvnw test); se vacía en cada prueba.
CREATE DATABASE quiniela_test OWNER quiniela ENCODING 'UTF8' TEMPLATE template0;
