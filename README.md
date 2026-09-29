# InkShare — perfil y colección en Firebase

App Android Kotlin/XML, paquete `com.manuelperez.inkshare`.

## Datos guardados

- Firebase Authentication autentica solo con Google y proporciona el UID.
- Firestore `users/{uid}` guarda `uid`, `username` y `createdAt`.
- `usernames/{nombre}` reserva ese nombre para el UID. El perfil y la reserva se crean en una única transacción; no se admiten nombres duplicados, alias ni cambios de propietario. Nombres: 3–20 caracteres, a-z, 0-9 y guion bajo; mayúsculas se convierten a minúsculas.
- `users/{uid}/collection/{cardId}_normal` y `{cardId}_foil` guardan `cardId`, `foil`, `quantity` y `updatedAt`. Solo existen documentos para cantidades positivas.
- `users/{uid}/friends/{friendUid}` guarda la lista personal de amigos, añadidos por nombre exacto.
- `users/{uid}/backups/collection` conserva una copia adicional opcional.

Ejemplo: `users/UID_DE_GOOGLE/collection/1_normal` → `{cardId: 1, foil: false, quantity: 3, updatedAt: ...}`.

## Inicio de sesión revisado

`InicioSesion` es el launcher. Google verifica la identidad mediante Firebase Auth. La app lee el perfil y comprueba que la reserva del nombre corresponde al mismo UID antes de permitir el acceso. En el primer acceso pide el nombre y espera a que Firestore confirme la transacción. Un error de perfil o conexión deja al usuario en el registro con opción de reintentar. Cambiar de cuenta limpia la sesión anterior y evita solapar el selector Google con su limpieza.

## Guardado de cartas

Cada suma o resta se guarda automáticamente en Firestore mediante una transacción. Usa la cantidad del servidor, evitando perder cambios de otro móvil. Un listener mantiene la colección actualizada. No hace falta pulsar «Crear copia adicional» para guardar cartas.

Las cantidades se muestran desde Firebase y su caché local. Cambiar cantidades requiere conexión; si no hay confirmación del servidor, la app muestra un error y no anuncia que se hayan guardado. La caché no sustituye al servidor. Las reglas aíslan la colección por UID.

Las colecciones locales de versiones anteriores se conservan. En Mi cuenta → Importar colección anterior se suben a Firebase; se prefiere la colección antigua de la cuenta actual y, si no existe, la anterior a las cuentas. La importación conserva la cantidad mayor por carta/acabado, sin sumar duplicados. Puede repetirse si se interrumpe. Importar una copia adicional usa el mismo criterio y no borra cartas existentes.

El catálogo inglés de 3.242 cartas y el OCR siguen disponibles. El OCR propone candidatos; confirma edición y acabado antes de añadir. Amigos es una lista personal sin solicitudes recíprocas. Préstamos sigue pendiente.

## Configuración necesaria

Consulta `FIREBASE_SETUP.md` para Google, huellas y `app/google-services.json`.

**Publica la versión actual de `firestore.rules` en tu consola Firebase.** Las reglas anteriores no autorizan la nueva subcolección `collection`; guardar el archivo en el ordenador no lo publica. Si compartes el proyecto Firebase con otras apps, integra las reglas antes de sustituirlas.

Compilar no habilita proveedores ni publica reglas. El registro y la colección requieren que Cloud Firestore exista, Google esté habilitado y las reglas estén publicadas.

## Pruebas

Android: `gradlew.bat testDebugUnitTest assembleDebug`.

Reglas: instala dependencias en `firebase-tests` y ejecuta `npm test`. Solo se usa Firestore Emulator con el proyecto `demo-inkshare`, nunca datos de producción. Se comprueban nombres únicos, permisos de perfiles/amigos, privacidad de colecciones, cantidades inválidas, cambios simultáneos, escucha entre dispositivos e importaciones repetidas.

Si la terminal de la app de escritorio produce `Unable to establish loopback connection`, configura temporalmente:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
New-Item -ItemType Directory -Force .java-tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\.java-tmp"
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

Prueba manual pendiente: Google real, creación de perfil, añadir carta y verificar documento en consola, cerrar sesión/reentrar, y usar dos dispositivos. Documentación: https://firebase.google.com/docs/firestore/manage-data/transactions y https://firebase.google.com/docs/firestore/query-data/listen.

## Cambio de paquete Android

El paquete actual es `com.manuelperez.inkshare`. Registra una nueva app Android con ese paquete en el proyecto Firebase `inkshare-7527e`, añade las huellas de firma indicadas en FIREBASE_SETUP.md y descarga su `google-services.json` en `app/`.

Los JSON del paquete anterior están guardados en la copia de seguridad externa al proyecto. No se deben editar manualmente para cambiarles el paquete. Hasta añadir el archivo nuevo, la app muestra que falta configurar Firebase.

Android instala este paquete como una aplicación distinta. Los datos locales del paquete anterior no se transfieren automáticamente; los datos de Firestore del mismo proyecto y UID se conservan.
