# 📻 Walkie-Talkie Pro (Android APK)

Aplicación Android Push-To-Talk (PTT) de ultra-baja latencia sobre Internet con soporte para rotación automática de cuentas en la nube.

---

## ⚡ Características Principales

* **Push-To-Talk (PTT) Físico y Táctil:**
  * Al mantener presionado el botón central PTT transmite en tiempo real.
  * Al soltarlo silencia el micrófono e inmediatamente emite el sonido táctico *"Roger Beep"*.
* **Motor de Voz Ultra Rápido:**
  * Desarrollado sobre el SDK de audio de **Agora.io** con cancelación de eco y reducción de ruido adaptativo.
* **Pool de Cuentas y Rotación Automática (Horas Ilimitadas):**
  * Conectado a **Supabase**.
  * Si una cuenta de Agora agota sus 10,000 minutos mensuales gratuitos, la aplicación detecta la suspensión y rota automáticamente a la siguiente cuenta de respaldo sin intervención del usuario.
* **Frecuencias / Canales Dinámicos:**
  * Los canales (General, Logística, Emergencias) se sincronizan en tiempo real desde Supabase.
* **Indicadores LED Tácticos:**
  * 🔴 **TX (Transmitting):** Encendido cuando estás hablando al canal.
  * 🟢 **RX (Receiving):** Encendido cuando alguien más está hablando en la frecuencia.

---

## 🚀 Compilación Automática del APK
Este repositorio cuenta con **GitHub Actions**. Cada vez que se actualiza el código, los servidores de GitHub compilan el archivo `app-debug.apk` y lo publican automáticamente en la sección de **Releases**.
