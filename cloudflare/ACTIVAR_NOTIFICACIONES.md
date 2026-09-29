# Activar notificaciones sin Blaze

La app y el Worker están preparados localmente. Este documento no significa que el servidor esté desplegado.

1. Mantener Firebase Spark y Cloudflare Workers Free. No vincular facturación ni activar servicios de pago.
2. En Google Cloud → IAM del proyecto inkshare-7527e, editar la cuenta de servicio inkshare-notificaciones y añadir **Usuario de Cloud Datastore (roles/datastore.user)** además de Firebase Cloud Messaging API Admin. Ese permiso permite al Worker leer los dispositivos y actualizar la cola de avisos. No conceder Propietario ni Editor. La cuenta de servicio accede mediante IAM; las reglas protegen a los usuarios de Android.
3. En Cloudflare, conservar el secreto FIREBASE_SERVICE_ACCOUNT con el JSON completo. Nunca copiar la clave al código, a Android, a Git ni a un chat.
4. Abrir worker.js de esta carpeta, copiar TODO su contenido y sustituir Hello World en Edit code de inkshare-notificaciones. Pulsar Deploy. Abrir Visit: debe aparecer un JSON que identifica InkShare notifications, version 2. Esto solo verifica que se desplegó, no verifica el envío.
5. En Settings → Trigger Events → Cron Triggers, añadir ejecución cada minuto: `* * * * *`. Cloudflare puede tardar varios minutos en aplicar el trigger. Los avisos no son instantáneos; se procesan como máximo dos eventos por minuto para limitar consumo. Una cola grande añade demora.
6. En Firebase → Firestore → Reglas, publicar TODO el archivo firestore.rules del proyecto Android, incluido el nuevo bloque notificationOutbox y users/devices. No necesita índices compuestos ni Cloud Functions.
7. Ejecutar la app actualizada en ambos móviles, iniciar sesión y permitir las notificaciones. Si se denegó el permiso, habilitarlo desde Ajustes de Android → Aplicaciones → InkShare → Notificaciones.
8. Con la app del destinatario cerrada (sin Forzar detención), enviar una solicitud desde la otra cuenta. Esperar el siguiente ciclo. Comprobar también aceptación/rechazo de amistad y préstamo y que tocar el aviso abre la pestaña correspondiente. Ambos teléfonos necesitan la versión nueva.

## Comprobación y límites

- En Firestore, el propietario del proyecto puede revisar notificationOutbox: pending, sent, no_devices, obsolete, expired o failed. Ningún cliente puede leer o editar esta cola. Las solicitudes se siguen borrando al resolverlas; la cola guarda solo metadatos de entrega, no las cartas del préstamo.
- En Observability del Worker, OUTBOX_READ_403 o DEVICE_READ_403 indica permisos de Datastore; FCM_403 indica permisos de mensajería/API; GOOGLE_AUTH indica el secreto o la cuenta. No publicar capturas que revelen claves ni tokens.
- Si no hay dispositivos registrados, la versión 2 mantiene el aviso pendiente y reintenta hasta que caduque a las 24 horas. En Mi cuenta → Comprobar notificaciones puedes verificar permisos y registro del teléfono. Los fallos temporales se reintentan con espera; los mensajes pendientes caducan a las 24 horas. No se envían rechazos por cancelaciones del solicitante.
- Android descarta mensajes de otra cuenta y recuerda IDs recientes para evitar avisos repetidos. No hay botones de aceptar/rechazar en el aviso.
- Las cuotas gratuitas también limitan Firestore y la CPU del Worker (10 ms por ejecución). No se garantiza la entrega al instante ni un servicio ilimitado. Revisar Metrics; si hay Error 1102, reducir el trabajo o ajustar el diseño sin pasar a planes de pago. La entrega real requiere validación con los móviles y el Worker desplegado.
- No se han configurado limpieza automática ni TTL de Firestore, que puede requerir facturación. Los documentos de entrega terminados pueden eliminarse manualmente más adelante; no eliminarlos mientras se está probando un reintento.

## Diagnóstico de esta revisión

El endpoint público respondía con version 1. No prueba que exista un Cron Trigger ni que la cuenta tenga permisos de Datastore. Tras copiar la versión 2 y desplegarla, comprobar Settings → Trigger Events y añadir el Cron de cada minuto. En IAM añadir roles/datastore.user a la misma cuenta cuyo JSON se guardó como secreto. Revisar Observability para confirmar ejecuciones scheduled. En la app actualizada usar Mi cuenta → Comprobar notificaciones. En notificationOutbox, lastError muestra códigos sin claves ni tokens. Los avisos antiguos con no_devices no se recuperan automáticamente: crear una solicitud nueva para probar.
