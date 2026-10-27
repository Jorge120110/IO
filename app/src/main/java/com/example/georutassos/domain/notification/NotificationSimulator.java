package com.example.georutassos.domain.notification;

import android.util.Log;

import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.domain.model.ApprovalStatus;

/**
 * Simulador de notificaciones locales / Push (FCM) para disparar alertas cuando:
 * 1) El SUPER_USUARIO cambia el estado de un usuario a APPROVED o REJECTED.
 * 2) Un ADMINISTRADOR asigna una nueva Orden de Emergencia a un AGENTE.
 */
public class NotificationSimulator {

    private static final String TAG = "GeoRutaNotificationSim";

    public interface NotificationListener {
        void onNotificationDispatched(String title, String message, String targetIdentifier);
    }

    private NotificationListener listener;

    public void setNotificationListener(NotificationListener listener) {
        this.listener = listener;
    }

    /**
     * Dispara la simulación de notificación local/push cuando un SUPER_USUARIO aprueba o rechaza
     * el registro de un usuario.
     */
    public void sendUserApprovalNotification(User user, ApprovalStatus newStatus) {
        String title = switch (newStatus) {
            case APPROVED -> "Registro Aprobado - GeoRuta SOS";
            case REJECTED -> "Registro Rechazado - GeoRuta SOS";
            case PENDING_APPROVAL -> "Registro en Revisión - GeoRuta SOS";
        };

        String message = switch (newStatus) {
            case APPROVED -> "Hola " + user.getFullName() + ", tu cuenta con rol " + user.getRole()
                    + " ha sido APROBADA por el Super Usuario. Ya puedes iniciar sesión.";
            case REJECTED -> "Hola " + user.getFullName() + ", tu solicitud de registro (DPI: "
                    + user.getDpi() + ") ha sido RECHAZADA.";
            case PENDING_APPROVAL -> "Tu cuenta permanece pendiente de aprobación.";
        };

        Log.i(TAG, "[PUSH/LOCAL SIM] To DPI=" + user.getDpi() + " | " + title + " -> " + message);
        if (listener != null) {
            listener.onNotificationDispatched(title, message, user.getDpi());
        }
    }

    /**
     * Dispara la simulación de alerta de despacho hacia el dispositivo del AGENTE.
     */
    public void sendEmergencyDispatchAlert(EmergencyOrder order) {
        String title = "ALERTA DE EMERGENCIA #" + order.getId();
        String message = "Nueva orden asignada al Agente ID " + order.getAssignedAgentId()
                + " hacia (" + order.getDestinationLat() + ", " + order.getDestinationLng() + ")";

        Log.i(TAG, "[PUSH/LOCAL SIM] AgentID=" + order.getAssignedAgentId() + " | " + title + " -> " + message);
        if (listener != null) {
            listener.onNotificationDispatched(
                    title,
                    message,
                    "AGENT_" + order.getAssignedAgentId()
            );
        }
    }
}
