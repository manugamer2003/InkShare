# Configuración de Google y Firestore

Paquete Android: `com.manuelperez.inkshare`.

## Firma de esta versión de depuración

Obtenida con `gradlew.bat signingReport` el 16/09/2026. En Firebase → Configuración del proyecto → Tu app Android → Huellas digitales, añade:

SHA-1:
```text
6B:2D:39:94:83:09:E8:B9:0D:A5:E0:D9:53:AF:D6:96:89:26:AF:06
```

SHA-256:
```text
92:5C:FC:FA:77:7A:28:A3:A9:80:DA:66:7D:25:EE:B4:E9:BE:AF:18:05:E0:47:92:E6:AA:F8:C1:2C:E4:F0:21
```

Son las huellas de debug de este ordenador; la publicación requerirá las del certificado de producción/Play App Signing.

## Pasos en consola

1. Authentication → Proveedores: habilita Google y selecciona el correo de soporte. Deshabilita otros proveedores si estaban habilitados.
2. Registra las huellas anteriores. El JSON instalado en `app/google-services.json` contiene clientes OAuth Android y web para el paquete actual; la huella del cliente Android coincide con la firma debug indicada arriba.
3. Firestore Database → Reglas: publica `firestore.rules`. Si existen reglas para otras aplicaciones en el mismo proyecto, intégralas antes de publicar para no alterar esas aplicaciones.
4. Sincroniza y ejecuta la app. El primer acceso Google pide un nombre de usuario y crea el documento `users/{uid}` junto con `usernames/{nombre}`.

La copia local del archivo de reglas no publica cambios en Firebase. Las pruebas de este cambio se han realizado con el emulador `demo-inkshare`, sin modificar datos ni configuración remotos.

## Cambio de paquete Android

El paquete actual es `com.manuelperez.inkshare`. Registra una nueva app Android con ese paquete en el proyecto Firebase `inkshare-7527e`, añade las huellas de firma indicadas en FIREBASE_SETUP.md y descarga su `google-services.json` en `app/`.

Los JSON del paquete anterior están guardados en la copia de seguridad externa al proyecto. No se deben editar manualmente para cambiarles el paquete. El archivo nuevo ya está instalado en el módulo `app`.

Android instala este paquete como una aplicación distinta. Los datos locales del paquete anterior no se transfieren automáticamente; los datos de Firestore del mismo proyecto y UID se conservan.
