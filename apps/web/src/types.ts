export type FileEntry = {
  id: string;
  name: string;
  size: number;
  mtime: number;
  sha256: string;
};

export type ServerEvent =
  | { type: 'snapshot'; files: FileEntry[]; clipboard: string }
  | { type: 'file_added'; id: string; name: string; size: number; mtime: number; sha256: string }
  | { type: 'file_removed'; id: string }
  | { type: 'clipboard'; text: string };

export type SendmeMode = 'lan' | 'hotspot';
