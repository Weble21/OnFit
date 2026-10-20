export const MAX_FILE_BYTES = 10 * 1024 * 1024;
export const FILE_ACCEPT = '.png,.jpg,.jpeg,.webp,.pdf,image/png,image/jpeg,image/webp,application/pdf';
const formats = {png:'image/png',jpg:'image/jpeg',jpeg:'image/jpeg',webp:'image/webp',pdf:'application/pdf'};
export async function validateAttachment(file) {
  if (!file || !file.size) throw new Error('비어 있는 파일은 사용할 수 없어요.');
  if (file.size > MAX_FILE_BYTES) throw new Error('10MB 이하의 파일을 선택해 주세요.');
  const extension = file.name.split('.').at(-1).toLowerCase();
  const mime = formats[extension];
  if (!mime || (file.type && file.type !== mime && file.type !== 'application/octet-stream')) throw new Error('JPG, PNG, WebP 이미지 또는 PDF만 사용할 수 있어요.');
  const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer());
  const starts = prefix => prefix.every((b,i)=>bytes[i]===b);
  const ascii = new TextDecoder('ascii').decode(bytes);
  const signature = mime==='image/png' ? starts([137,80,78,71,13,10,26,10])
    : mime==='image/jpeg' ? starts([255,216,255])
    : mime==='image/webp' ? ascii.startsWith('RIFF') && ascii.slice(8,12)==='WEBP'
    : ascii.startsWith('%PDF-');
  if (!signature) throw new Error('파일 내용과 형식이 맞지 않아요. 원본 파일을 다시 확인해 주세요.');
  return { mime, kind: mime==='application/pdf'?'pdf':'image' };
}
/** Screenshots arrive from the clipboard as "image.png" or with no name; give them a readable one with the right extension. */
export function pastedFile(file) {
  const extension = Object.keys(formats).find(key => formats[key] === file.type);
  // An unsupported type keeps its name, so validation reports the format rather than a renamed file.
  if (!extension || (file.name && !/^image\.\w+$/i.test(file.name))) return file;
  return new File([file], '붙여넣은 이미지.' + extension, { type: file.type, lastModified: file.lastModified });
}
export function fileSize(bytes) {
  return bytes < 1024*1024 ? Math.max(1,Math.round(bytes/1024))+' KB' : (bytes/(1024*1024)).toFixed(1)+' MB';
}

