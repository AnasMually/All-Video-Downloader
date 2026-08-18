from pathlib import Path

translations = {
    'values-hi': ('मीडिया तैयार किया जा रहा है… %1$d%%', 'डाउनलोड पूरा नहीं हो सका। फिर से कोशिश करें या दूसरी क्वालिटी चुनें।'),
    'values-ru': ('Подготовка файла… %1$d%%', 'Не удалось завершить загрузку. Повторите попытку или выберите другое качество.'),
    'values-pt': ('Processando arquivo… %1$d%%', 'Não foi possível concluir o download. Tente novamente ou escolha outra qualidade.'),
    'values-id': ('Memproses file… %1$d%%', 'Unduhan tidak dapat diselesaikan. Coba lagi atau pilih kualitas lain.'),
    'values-bn': ('ফাইল প্রস্তুত করা হচ্ছে… %1$d%%', 'ডাউনলোড সম্পন্ন করা যায়নি। আবার চেষ্টা করুন অথবা অন্য মান বেছে নিন।'),
    'values-fr': ('Préparation du fichier… %1$d%%', 'Impossible de terminer le téléchargement. Réessayez ou choisissez une autre qualité.'),
    'values-es': ('Procesando archivo… %1$d%%', 'No se pudo completar la descarga. Inténtalo de nuevo o elige otra calidad.'),
    'values-zh-rCN': ('正在处理文件… %1$d%%', '无法完成下载。请重试或选择其他画质。'),
}

for folder, (processing, failed) in translations.items():
    path = Path('app/src/main/res') / folder / 'strings.xml'
    text = path.read_text(encoding='utf-8')
    if 'name="processing_media"' in text or 'name="download_failed_user"' in text:
        raise SystemExit(f'Unexpected existing final strings in {path}')
    anchor = '</resources>'
    block = (
        f'    <string name="processing_media">{processing}</string>\n'
        f'    <string name="download_failed_user">{failed}</string>\n'
    )
    if anchor not in text:
        raise SystemExit(f'No resources end tag in {path}')
    path.write_text(text.replace(anchor, block + anchor, 1), encoding='utf-8')
