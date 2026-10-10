const isIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent);

/**
 * Hands a generated file to the user. On iOS the share sheet is the dependable way out of a home-screen
 * app ("Save to Files", "Add to Calendar"); elsewhere a normal download.
 */
export async function saveFile(name: string, mime: string, content: string): Promise<void> {
  const file = new File([content], name, { type: mime });
  if (isIos() && navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file] });
      return;
    } catch (e) {
      if ((e as { name?: string }).name === "AbortError") return;
    }
  }
  const url = URL.createObjectURL(file);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
