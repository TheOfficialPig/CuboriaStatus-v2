from PIL import Image,ImageDraw,ImageFont
import numpy as np
from pathlib import Path
res=Path('/mnt/data/cuboria_v09_work/src/main/resources/dashboard-assets')
a=np.asarray(Image.open('/mnt/data/image(20261015-012541).png').convert('RGB')).copy()
rng=np.random.default_rng(27)
boxes=[(368,404,735,477,765),(1070,400,1452,473,1470),(979,611,1173,670,1177),(1286,611,1456,670,1480),(979,691,1173,750,1177),(1286,691,1456,750,1480),(380,841,849,883,857)]
for x1,y1,x2,y2,sx in boxes:
 for y in range(y1,y2):
  # average local right-side sample at the same Y, preserve original shading gradient
  val=np.median(a[y,max(sx-8,0):min(sx+8,a.shape[1])],axis=0)
  row=np.clip(val+rng.integers(-2,3,size=(x2-x1,1)),0,255).astype('uint8')
  a[y,x1:x2]=row
Image.fromarray(a).save(res/'template-base.png')
chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 /.-:_!?@#+%()'
CW,CH,COLS=43,49,12
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf',44)
atlas=Image.new('RGBA',(CW*COLS,CH*((len(chars)+COLS-1)//COLS)))
for n,c in enumerate(chars):
 if c==' ':continue
 tile=Image.new('RGBA',(CW,CH));d=ImageDraw.Draw(tile)
 # Crop glyph bbox to maximize draw within the cell, unlike initial atlas
 bbox=d.textbbox((0,0),c,font=font,stroke_width=1)
 glyph=Image.new('RGBA',(bbox[2]-bbox[0]+2,bbox[3]-bbox[1]+2))
 gd=ImageDraw.Draw(glyph)
 gd.text((1-bbox[0],1-bbox[1]),c,font=font,fill='#e4f9ff',stroke_width=2,stroke_fill='#03142e')
 if glyph.width>37 or glyph.height>43:
  scale=min(37/glyph.width,43/glyph.height);glyph=glyph.resize((int(glyph.width*scale),int(glyph.height*scale)),Image.Resampling.NEAREST)
 tile.alpha_composite(glyph,((CW-glyph.width)//2,(CH-glyph.height)//2))
 atlas.alpha_composite(tile,((n%COLS)*CW,(n//COLS)*CH))
atlas.save(res/'font-atlas.png')
