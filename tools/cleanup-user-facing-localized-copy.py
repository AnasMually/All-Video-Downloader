from pathlib import Path
import re

copy = {
    'values-zh-rCN': {
        'audio_downloaded_and_merged': '视频和音频将自动合并。',
        'm4a_media3_note': '需要转换时，音频将保存为 M4A。',
        'preparing_download_engine': '正在准备下载…',
        'engine_version': '引擎版本：%1$s',
    },
    'values-hi': {
        'audio_downloaded_and_merged': 'वीडियो और ऑडियो अपने आप जोड़ दिए जाएंगे।',
        'm4a_media3_note': 'ज़रूरत होने पर ऑडियो M4A में सेव होगा।',
        'preparing_download_engine': 'डाउनलोड तैयार किया जा रहा है…',
        'engine_version': 'इंजन संस्करण: %1$s',
    },
    'values-es': {
        'audio_downloaded_and_merged': 'El video y el audio se combinarán automáticamente.',
        'm4a_media3_note': 'El audio se guardará como M4A cuando sea necesario convertirlo.',
        'preparing_download_engine': 'Preparando la descarga…',
        'engine_version': 'Versión del motor: %1$s',
    },
    'values-fr': {
        'audio_downloaded_and_merged': 'La vidéo et l’audio seront combinés automatiquement.',
        'm4a_media3_note': 'L’audio sera enregistré en M4A lorsqu’une conversion est nécessaire.',
        'preparing_download_engine': 'Préparation du téléchargement…',
        'engine_version': 'Version du moteur : %1$s',
    },
    'values-bn': {
        'audio_downloaded_and_merged': 'ভিডিও ও অডিও স্বয়ংক্রিয়ভাবে একত্র করা হবে।',
        'm4a_media3_note': 'প্রয়োজন হলে অডিও M4A হিসেবে সংরক্ষণ করা হবে।',
        'preparing_download_engine': 'ডাউনলোড প্রস্তুত করা হচ্ছে…',
        'engine_version': 'ইঞ্জিন সংস্করণ: %1$s',
    },
    'values-pt': {
        'audio_downloaded_and_merged': 'O vídeo e o áudio serão combinados automaticamente.',
        'm4a_media3_note': 'O áudio será salvo como M4A quando a conversão for necessária.',
        'preparing_download_engine': 'Preparando o download…',
        'engine_version': 'Versão do mecanismo: %1$s',
    },
    'values-ru': {
        'audio_downloaded_and_merged': 'Видео и аудио будут объединены автоматически.',
        'm4a_media3_note': 'При необходимости преобразования аудио будет сохранено в M4A.',
        'preparing_download_engine': 'Подготовка загрузки…',
        'engine_version': 'Версия движка: %1$s',
    },
    'values-id': {
        'audio_downloaded_and_merged': 'Video dan audio akan digabungkan secara otomatis.',
        'm4a_media3_note': 'Audio akan disimpan sebagai M4A jika perlu dikonversi.',
        'preparing_download_engine': 'Menyiapkan unduhan…',
        'engine_version': 'Versi mesin: %1$s',
    },
}

for folder, values in copy.items():
    path = Path('app/src/main/res') / folder / 'strings.xml'
    text = path.read_text(encoding='utf-8')
    for key, value in values.items():
        pattern = rf'<string name="{re.escape(key)}">.*?</string>'
        replacement = f'<string name="{key}">{value}</string>'
        text, count = re.subn(pattern, replacement, text, count=1)
        if count != 1:
            raise SystemExit(f'Missing or duplicate string {key} in {path}')
    path.write_text(text, encoding='utf-8')
