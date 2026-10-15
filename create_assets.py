from PIL import Image, ImageDraw, ImageFont
from pathlib import Path
import json, shutil
root=Path('/mnt/data/cuboria_v09_work')
res=root/'src/main/resources/dashboard-assets';res.mkdir(parents=True,exist_ok=True)
img=Image.open('/mnt/data/image(20261015-012541).png').convert('RGB')
# Clear only changing text; fill with texture from same horizontal color bands.
# destination boxes carefully within recessed dark panels; no frame or labels.
areas=[
 (370,408,755,483,800,408), # main total from matching empty area farther right in same panel? use pixel color sampled
 (1054,404,1471,476,1455,409),
 (981,611,1187,666,795,612),
 (1290,610,1458,669,1193,613),
 (981,690,1190,751,792,696),
 (1290,690,1458,751,1193,700),
 (378,833,855,885,600,800),
]
# use row-average sampled from nearby blank panel with subtle tiny grain, avoiding baked glyphs
import numpy as np
arr=np.array(img)
for x1,y1,x2,y2,sx,sy in areas:
    # patch from local blank middle right or left, replicate strip with low-frequency color
    if y1<500:
        sample=arr[y2-8:y2-4,x1:x2]
    elif y1<780:
        sample=arr[y2-10:y2-6,x1:x2]
    else:
        sample=arr[y2-8:y2-5,x1:x2]
    med=np.median(sample.reshape(-1,3),axis=0).astype('uint8')
    # gentle texture from local blank sample, horizontal variation
    arr[y1:y2,x1:x2]=med
img=Image.fromarray(arr)
img.save(res/'template-base.png',optimize=True)
# Keep exact pixels of online sign; recolor pixels for offline version with rectangle state overlay, retaining frame
base=np.array(Image.open('/mnt/data/image(20261015-012541).png').convert('RGBA'))
# sign inset bounded by decorative frame (full detail retained)
x1,y1,x2,y2=1319,157,1609,268
sign=Image.fromarray(base[y1:y2,x1:x2].copy())
# trim left border only? keep full rectangular inset for exact visual continuity
sign.save(res/'sign-online.png')
a=np.array(sign)
r,g,b=a[:,:,0].copy(),a[:,:,1].copy(),a[:,:,2].copy()
mask=(g>r*1.25)&(g>b*1.13)&(g>65)
a[mask,0]=np.clip(g[mask]*1.04,0,255).astype('uint8')
a[mask,1]=np.clip(r[mask]*0.44+12,0,255).astype('uint8')
a[mask,2]=np.clip(r[mask]*0.38+22,0,255).astype('uint8')
# Label ONLINE remains in red version -> explicit text overlay in renderer needed to show OFFLINE
Image.fromarray(a).save(res/'sign-offline.png')
# Independent artwork assets preserved in bundle, not stretched into final base.
for f in ['logo','character-left','character-right','players','network','header','row','footer','footer-address','footer-update','oneblock','arcade']:
    p=res/(f+'.png')
    if p.exists():continue
# fixed-cell font atlas. Exact text renderer uses cell coordinates, does not assume generative atlas reliable.
font_path='/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf'
font=ImageFont.truetype(font_path,30)
charset='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 /.-:_!?@#+%()'
CW,CH,COLS=43,49,12
atlas=Image.new('RGBA',(CW*COLS,CH*((len(charset)+COLS-1)//COLS)),(0,0,0,0));d=ImageDraw.Draw(atlas)
for i,ch in enumerate(charset):
    if ch==' ':continue
    x=(i%COLS)*CW;y=(i//COLS)*CH
    # thick dark outline and bright bevel
    d.text((x+5,y+2),ch,font=font,fill='#dff9ff',stroke_width=2,stroke_fill='#06102d')
    d.text((x+5,y+3),ch,font=font,fill='#d9f6ff',stroke_width=1,stroke_fill='#133b6c')
atlas.save(res/'font-atlas.png')
(res/'font-atlas.txt').write_text(charset+'\n')
print('template',img.size, 'font',atlas.size)
