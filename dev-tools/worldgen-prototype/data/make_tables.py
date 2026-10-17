# Builds planes.tsv and sets.tsv for prototype C from prototype B's plane table and the typed master pool.
import re
src = open(r'../reference/BubbleProto.java', encoding='utf-8').read()
rows = re.findall(r'\{"([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)", "([0-9A-F]+)", "([^"]+)"\}', src)
SQUARE = {"Ravnica", "Capenna", "Kaladesh/Avishkar", "Mirrodin/New Phyrexia"}
plane_of = {}
with open('planes.tsv', 'w', encoding='utf-8', newline='\n') as f:
    f.write('#plane\tT\tM\tE\tcoast\tislandPref\tcolor\tshape\tcohesionExempt\n')
    for r in rows:
        sets = r[7].split()
        if r[0] == 'Arcavios': sets.append('STX')
        for s in sets: plane_of[s] = r[0]
        f.write('\t'.join([r[0], r[1], r[2], r[3], r[4], r[5], r[6],
                           'square' if r[0] in SQUARE else 'round',
                           '1' if r[0].startswith('Crossroads') else '0']) + '\n')
CORE = set("LEA LEB 2ED 3ED 4ED 5ED 6ED 7ED 8ED 9ED 10E M10 M11 M12 M13 M14 M15 ORI M19 M20 M21 FDN".split())
REMASTER = {'AKR': 'AKH', 'KLR': 'KLD', 'TPR': 'TMP', 'TSR': 'TSP', 'DMR': 'DOM', 'SIR': 'SOI', 'INR': 'ISD', 'RVR': 'RAV', 'HBG': 'AFR'}
GENERIC = set("CHR ME1 ME2 ME3 ME4 VMA MMA MM2 MM3 EMA IMA A25 UMA 2XM 2X2 PIO 30A".split())
codes = [l.split('\t') for l in open('master_typed.tsv', encoding='utf-8').read().splitlines()]
have = {c[0] for c in codes}
n = 0
with open('sets.tsv', 'w', encoding='utf-8', newline='\n') as f:
    f.write('#code\ttype\tdate\tname\tplane\tcore\tfoldInto\n')
    for code, typ, date, name in codes:
        fold = '-'
        if code.startswith('Y') and code[1:] in have: fold = code[1:]
        elif code in REMASTER: fold = REMASTER[code]
        elif code in GENERIC: fold = '@CORE'
        plane = plane_of.get(code) or plane_of.get(fold) or 'Crossroads (Multiversal)'
        f.write('\t'.join([code, typ, date, name, plane, '1' if code in CORE else '0', fold]) + '\n'); n += 1
print(n, 'sets;', sum(1 for c in codes if c[0] in CORE), 'core')
