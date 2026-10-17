import { useEffect, useState } from "react";
import { platform } from "@/platform";

/** One progress indicator survives route changes and covers mandatory updates too. */
export default function AndroidUpdateProgress() {
  const [progress, setProgress] = useState(null);
  const [cancelling, setCancelling] = useState(false);
  useEffect(() => {
    if (platform.os !== "android") return;
    return platform.onUpdateProgress?.((event) => {
      setCancelling(false);
      setProgress(["downloading", "verifying"].includes(event.state) ? event : null);
    });
  }, []);
  if (!progress) return null;
  return (
    <div role="status" aria-live="polite" className="fixed inset-x-3 bottom-24 z-[250] mx-auto max-w-md rounded-xl border border-border bg-surface p-4 text-sm text-text shadow-xl">
      <p>{progress.state === "verifying" ? "Vérification de la mise à jour…" : `Téléchargement de la mise à jour${progress.percent >= 0 ? ` : ${progress.percent}%` : "…"}`}</p>
      {progress.state === "downloading" && <progress className="mt-2 h-2 w-full accent-primary" max="100" {...(progress.percent >= 0 ? { value: progress.percent } : {})} />}
      <button className="mt-2 min-h-11 px-3 text-primary" disabled={cancelling} onClick={async () => {
        setCancelling(true);
        try { await platform.cancelUpdate(); } catch { setCancelling(false); }
      }}>{cancelling ? "Annulation…" : "Annuler"}</button>
    </div>
  );
}
