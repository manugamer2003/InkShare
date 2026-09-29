# Préstamos

La pantalla Préstamos se abre desde el inicio. Activos muestra préstamos entregados y recibidos; Históricos conserva los cerrados. Solo el creador registra la devolución completa. Ambos participantes reciben las actualizaciones mediante un listener de Firestore.

Cada documento loans/{id} incluye lenderUid, borrowerUid, sus nombres públicos, participants, items, status, createdAt y closedAt. Las cartas se guardan con identificador, nombre, acabado y cantidad. El borrador admite 10 cartas/acabados distintos; seleccionar de nuevo una carta/acabado sustituye su cantidad.

Este es un registro de entregas: las cantidades de la colección no cambian y no se reservan existencias entre préstamos simultáneos. La creación comprueba las copias de la colección en una transacción. Las operaciones necesitan conexión y conservan el mismo identificador al reintentar la creación.

## Activación en Firebase

Antes de usar la pantalla, publica el archivo firestore.rules actualizado en Firebase Console > Firestore Database > Reglas, dentro del proyecto inkshare-7527e. El archivo local no despliega las reglas. Si hay cambios remotos ajenos a este proyecto, integra la sección loans y sus funciones validLoanItem/validLoanItems sin sustituir esos cambios.

La consulta usa participants array-contains UID; no necesita un índice compuesto. Los préstamos no se eliminan al quitar al amigo de la lista. Solo sus dos participantes pueden consultar un préstamo existente; solo el creador puede cerrarlo. No se permite borrar ni reabrir históricos.
