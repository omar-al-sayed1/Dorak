import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { useT } from "./i18n";
import { Icon } from "./icons";

/**
 * Toasts, as the classic pages show them (same CSS): a success one leaves after 5 seconds (the line
 * along its bottom counts down), an error stays until closed. One can offer Undo, which runs its
 * action and closes it.
 */
export interface ToastInput {
  text: string;
  error?: boolean;
  undo?: () => unknown;
}

interface Toast extends ToastInput {
  id: number;
}

const ToastContext = createContext<(toast: ToastInput) => void>(() => {});

export function useToast(): (toast: ToastInput) => void {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(1);
  const show = useCallback((toast: ToastInput) => {
    const id = next.current++;
    setToasts((shown) => [...shown.slice(-2), { ...toast, id }]);
  }, []);
  const close = useCallback((id: number) => setToasts((shown) => shown.filter((toast) => toast.id !== id)), []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((toast) => <ToastItem key={toast.id} toast={toast} onClose={close} />)}
      </div>
    </ToastContext.Provider>
  );
}

function ToastItem({ toast, onClose }: { toast: Toast; onClose: (id: number) => void }) {
  const t = useT();
  useEffect(() => {
    if (toast.error) {
      return;
    }
    const timer = setTimeout(() => onClose(toast.id), 5000);
    return () => clearTimeout(timer);
  }, [toast.error, toast.id, onClose]);
  return (
    <div className={toast.error ? "toast error" : "toast"} role={toast.error ? "alert" : "status"}>
      <span className="toast-icon"><Icon name={toast.error ? "alert" : "check-circle"} /></span>
      <p>{toast.text}</p>
      {toast.undo && (
        <button type="button" className="toast-undo" onClick={() => { toast.undo?.(); onClose(toast.id); }}>{t("action.undo")}</button>
      )}
      <button type="button" className="toast-close" aria-label={t("toast.close")} onClick={() => onClose(toast.id)}><Icon name="x" /></button>
    </div>
  );
}
