# Revisión de Firebase — 16/09/2026

## Hallazgo y corrección

El perfil ya se guardaba en `users/{uid}` y el índice de nombres en `usernames/{nombre}`. Sin embargo, las cantidades se guardaban en SharedPreferences y solo llegaban a Firebase mediante una copia manual. Eso no cumplía el guardado automático solicitado.

Ahora la colección usa `users/{uid}/collection/{cardId}_{normal|foil}`. Cada cambio usa una transacción con la cantidad del servidor y el listener actualiza los dispositivos de la misma cuenta. La app no indica éxito hasta recibir confirmación del servidor. Las modificaciones necesitan conexión; la lectura puede mostrar caché identificada como tal.

En el acceso se comprueba que perfil, UID y reserva de nombre coinciden antes de abrir el inicio. Se limpia la sesión en memoria al revisar el acceso; la UI permite reintentar fallos de verificación y no solapa el cambio de cuenta Google con la limpieza del selector.

## Verificación

- `testDebugUnitTest assembleDebug`: BUILD SUCCESSFUL.
- 14 pruebas Android/JVM, sin fallos.
- 15 pruebas en Firestore Emulator, sin fallos: perfiles, nombres únicos, permisos, amigos, colección por UID, transacciones simultáneas, cantidades/identificadores inválidos, escucha entre dispositivos e importación idempotente.
- Datos del emulador: proyecto `demo-inkshare`, sin modificar producción.
- Launcher verificado: `InicioSesion`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Pendiente externo

La comprobación de Firebase CLI no pudo autenticar el acceso remoto; no se publicaron reglas ni se modificaron datos de producción. Publicar la versión actual de `firestore.rules` en Firestore → Reglas del proyecto `inkshare-7527e` es necesario para habilitar `collection`.

Pendiente probar Google real y la app en dispositivo con esa configuración remota. Las pruebas de compilación y emulador no validan el OAuth del proyecto real.
