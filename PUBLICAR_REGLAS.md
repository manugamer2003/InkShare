# Activar el guardado en tu Firebase

1. Abre https://console.firebase.google.com/project/inkshare-7527e/firestore/rules.
2. En Firestore Database → Reglas, publica el contenido de `firestore.rules` de esta carpeta. Si tienes reglas de otras aplicaciones en este mismo proyecto, intégralas antes de sustituirlas.
3. Ejecuta de nuevo InkShare2. Inicia sesión con Google y completa el nombre si es tu primer acceso.
4. En Firestore → Datos verás `users` → tu UID, con `uid`, `username` y `createdAt`.
5. Añade una carta. Dentro de ese UID aparecerá la subcolección `collection`, con un documento por carta y acabado. Por ejemplo, `1_normal` contiene `cardId`, `foil`, `quantity` y `updatedAt`.

Las cartas se guardan automáticamente; las copias adicionales ya no son necesarias para guardarlas. Si tienes cartas de la versión anterior solo en el móvil, usa Mi cuenta → Importar colección anterior.

Para cambios de cantidad necesitas conexión. La pantalla confirma cuándo Firebase ha guardado el cambio y distingue la vista de caché.
