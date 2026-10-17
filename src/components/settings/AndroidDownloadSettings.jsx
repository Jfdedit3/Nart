import { useEffect, useState } from "react";
import { HardDrive, Wifi, Trash2 } from "lucide-react";
import { getDownloadStorage, configureDownloads } from "@/api/downloads";
import { getEpisodeProgress } from "@/api/progress";
import { useAuthStore } from "@/stores/useAuthStore";
import { useDownloadsStore } from "@/stores/useDownloadsStore";
import { toast } from "@/lib/toast";
import { Select } from "@/components/ui/Select";
import { SettingRow, Toggle } from "./SettingsLayout";

const gib = 1024 ** 3;
const size = (bytes) => `${(bytes / gib).toFixed(1)} Go`;

export default function AndroidDownloadSettings() {
  const [info, setInfo] = useState(null);
  const [busy, setBusy] = useState(false);
  const [candidates, setCandidates] = useState(null);
  const user = useAuthStore((s) => s.user);
  useEffect(() => {
    let live = true;
    const refresh = () => getDownloadStorage().then((value) => { if (live) setInfo(value); }).catch(() => {});
    void refresh();
    const timer = setInterval(refresh, 10_000);
    return () => { live = false; clearInterval(timer); };
  }, []);

  const configure = async (patch) => {
    setBusy(true);
    try {
      await configureDownloads({ wifiOnly: info.wifiOnly, quotaBytes: info.quotaBytes, ...patch });
      setInfo(await getDownloadStorage());
    } catch (error) { toast.error(error.message || "Réglage impossible"); }
    finally { setBusy(false); }
  };

  const findWatched = async () => {
    setBusy(true);
    try {
      await useDownloadsStore.getState().refresh();
      const matches = [];
      for (const item of useDownloadsStore.getState().items) {
        if (item.status !== "done" || item.type === "scan") continue;
        const progress = await getEpisodeProgress(item.slug, item.seasonId, item.ep, item.lang, user?.id);
        if (progress?.completed) matches.push(item);
      }
      setCandidates(matches);
      if (!matches.length) toast.info("Aucun épisode terminé à nettoyer.");
    } catch { toast.error("Vérification du visionnage impossible. Aucun fichier supprimé."); }
    finally { setBusy(false); }
  };

  const clean = async () => {
    setBusy(true);
    try {
      // Recheck native status: a stale preview must never delete an active transfer.
      await useDownloadsStore.getState().refresh();
      const current = useDownloadsStore.getState().byId;
      const ids = candidates.filter((item) => current[item.id]?.status === "done").map((item) => item.id);
      await useDownloadsStore.getState().removeMany(ids);
      setCandidates(null);
      setInfo(await getDownloadStorage());
      toast.success(`${ids.length} épisode(s) supprimé(s)`);
    } catch { toast.error("Nettoyage incomplet"); }
    finally { setBusy(false); }
  };

  if (!info) return null;
  return (
    <>
      <SettingRow icon={Wifi} label="Wi-Fi uniquement"
        description="Met les transferts en pause hors Wi-Fi non limité. Ils reprennent lorsque le Wi-Fi revient et que l’application est visible.">
        <Toggle label="Télécharger uniquement en Wi-Fi" checked={info.wifiOnly}
          onChange={(wifiOnly) => { if (!busy) void configure({ wifiOnly }); }} />
      </SettingRow>
      <SettingRow icon={HardDrive} label="Stockage hors ligne"
        description={`${size(info.usedBytes)} utilisés · ${size(info.freeBytes)} libres`}
        details="Le quota inclut les fichiers partiels. Une réserve de 100 Mo est conservée. Les fichiers existants ne sont pas effacés lorsque le quota baisse.">
        <Select title="Quota de stockage" value={String(info.quotaBytes / gib)}
          onValueChange={(value) => { if (!busy) void configure({ quotaBytes: Number(value) * gib }); }}
          options={[{ value: "0", label: "Sans quota" }, ...[1, 2, 5, 10, 20, 50].map((n) => ({ value: String(n), label: `${n} Go` }))]} />
      </SettingRow>
      <SettingRow icon={Trash2} label="Nettoyer les épisodes regardés"
        description={candidates?.length ? `${candidates.length} épisode(s), environ ${size(candidates.reduce((total, item) => total + (item.sizeBytes || 0), 0))}. Confirme leur suppression.` : "Recherche les épisodes téléchargés marqués comme terminés sur ton compte. Une connexion est nécessaire."}>
        <div className="flex flex-wrap gap-2">
          {candidates?.length > 0 ? <>
            <button className="btn-shu" disabled={busy} onClick={clean}>Supprimer {candidates.length} épisode(s)</button>
            <button className="px-3 py-2 text-sm" disabled={busy} onClick={() => setCandidates(null)}>Annuler</button>
          </> : <button className="btn-shu" disabled={busy || !user?.id || !navigator.onLine} onClick={findWatched}>
            {busy ? "Vérification…" : "Rechercher"}
          </button>}
        </div>
      </SettingRow>
    </>
  );
}
