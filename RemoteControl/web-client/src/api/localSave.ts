export interface DirectoryPickerWindow extends Window {
  showDirectoryPicker?: (options?: {
    mode?: 'read' | 'readwrite';
  }) => Promise<FileSystemDirectoryHandle>;
}

export function isDirectoryPickerSupported(): boolean {
  if (typeof window === 'undefined') return false;
  const w = window as DirectoryPickerWindow;
  return Boolean(window.isSecureContext && typeof w.showDirectoryPicker === 'function');
}

export async function pickSaveDirectory(): Promise<FileSystemDirectoryHandle> {
  const w = window as DirectoryPickerWindow;
  if (!w.showDirectoryPicker) {
    throw new Error('Directory picker requires HTTPS or localhost in Chromium');
  }
  return w.showDirectoryPicker({ mode: 'readwrite' });
}

export async function saveBlobToDirectory(
  dirHandle: FileSystemDirectoryHandle,
  filename: string,
  blob: Blob,
): Promise<void> {
  const fileHandle = await dirHandle.getFileHandle(filename, { create: true });
  const writable = await fileHandle.createWritable();
  try {
    await writable.write(blob);
  } finally {
    await writable.close();
  }
}

export async function readFileFromDirectory(
  dirHandle: FileSystemDirectoryHandle,
  filename: string,
): Promise<File> {
  const fileHandle = await dirHandle.getFileHandle(filename);
  return fileHandle.getFile();
}

export function triggerBrowserDownload(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 2000);
}
