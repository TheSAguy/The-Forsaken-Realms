"""Round 668: Jace's Echo - the Jace the five "Way" necklaces promise.

The Theorist and Tam drop five necklaces from Realm of Legends 1.077, each starting a battle with one of Reality
Fracture's "Way of the ..." enchantments. Each Way empowers Jace 5 as it ENTERS - and a card an item starts in play
never enters (the agent game, round 668: Way of the Mind Sculptor on turn 1, no Jace token), so every necklace was a
planeswalker bonus with no planeswalker. Jace's Echo sits in the command zone beside it and empowers Jace 5 at the
first upkeep (the Pledges' once-a-duel pattern, round 630) - the token the Way's own text describes.

Writes common/custom_cards/jaces_echo.txt and common/custom_card_pics/Jace's Echo.fullborder.png: the Jace boss
effect's blue frame, art from the cached Forge image of The Theorist, Jace Beleren (Ekaterina Burmak; needs the Forge
card cache).

usage: python make_jaces_echo.py <repo root> [--preview out.png]"""
import os
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFilter

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'walker_pledges'))
import make_pledges as mp  # noqa: E402

NAME = "Jace's Echo"
ORACLE = ('At the beginning of your first upkeep, empower Jace 5. (Put five loyalty counters on a Jace token you '
          'control. If you don\'t control one, first create a blue Jace planeswalker token with "[-1]: Surveil 1" and '
          '"[-3]: Draw a card.")')
FLAVOR = 'Nothing the Theorist knew is ever quite lost.'
TEMPLATE = 'Jace Boss Effect.fullborder.jpg'
ART = ('FRA', 'The Theorist, Jace Beleren1.full.jpg')
ART_BOX = (40, 64, 448, 336)
ARTIST = 'Ekaterina Burmak'


def card_script():
    return '\n'.join([
        'Name:' + NAME,
        'ManaCost:no cost',
        'Colors:blue',
        'Types:Enchantment',
        'T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Command | CheckSVar$ YourTurns | '
        'SVarCompare$ EQ1 | Execute$ TrigEmpower | TriggerDescription$ ' + ORACLE,
        'SVar:TrigEmpower:DB$ Empower | Type$ Jace | Num$ 5',
        'SVar:YourTurns:Count$YourTurns',
        'Oracle:' + ORACLE,
    ]) + '\n'


ART_WINDOW = (37, 76, 449, 423)  # above the type bar of this full-art frame
TEXT_BOX = (37, 466, 449, 621)  # the frame's dark see-through box: its old text blurred away, ours in white


def draw_face(common):
    face = Image.open(os.path.join(common, 'custom_card_pics', TEMPLATE)).convert('RGB')
    if face.size != (486, 680):
        face = face.resize((486, 680), Image.LANCZOS)
    d = ImageDraw.Draw(face)
    mp._row_fill(face, (38, 36, 400, 67), (404, 444))
    d.text((44, 52), NAME, font=mp.font('georgiab.ttf', 27), fill=(16, 14, 12), anchor='lm')
    src = Image.open(os.path.join(mp.CACHE, *ART)).convert('RGB')
    w, h = ART_WINDOW[2] - ART_WINDOW[0], ART_WINDOW[3] - ART_WINDOW[1]
    x0, y0, x1, y1 = ART_BOX
    cw = int((y1 - y0) * w / h)  # the window is taller than the card's art box: crop narrower, centered on Jace
    cx = (x0 + x1) // 2 - 10
    art = src.crop((cx - cw // 2, y0, cx + cw // 2, y1)).resize((w, h), Image.LANCZOS)
    face.paste(art, ART_WINDOW[:2])
    box = face.crop(TEXT_BOX).filter(ImageFilter.GaussianBlur(10))
    box = Image.blend(box, Image.new('RGB', box.size, (8, 18, 34)), 0.55)
    face.paste(box, TEXT_BOX[:2])
    y = TEXT_BOX[1] + 6
    for line in textwrap.wrap(ORACLE, 40):
        d.text((46, y), line, font=mp.font('georgia.ttf', 17), fill=(240, 244, 250))
        y += 20
    y += 5
    for line in textwrap.wrap(FLAVOR, 46):
        d.text((46, y), line, font=mp.font('georgiai.ttf', 16), fill=(196, 210, 228))
        y += 20
    d.rectangle((26, 628, 215, 664), fill=(0, 0, 0))
    d.text((30, 635), 'TFR', font=mp.font('georgiab.ttf', 13), fill=(235, 235, 235))
    d.text((30, 650), 'Illus. ' + ARTIST, font=mp.font('georgia.ttf', 13), fill=(235, 235, 235))
    return face


def main(argv):
    repo = argv[1]
    common = os.path.join(repo, 'forge-gui', 'res', 'adventure', 'common')
    face = draw_face(common)
    if '--preview' in argv:
        face.save(argv[argv.index('--preview') + 1])
        return
    with open(os.path.join(common, 'custom_cards', 'jaces_echo.txt'), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(card_script())
    face.save(os.path.join(common, 'custom_card_pics', NAME + '.fullborder.png'))
    print('wrote', NAME)


if __name__ == '__main__':
    main(sys.argv)
