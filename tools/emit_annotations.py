"""Print a text file as GitHub ::notice annotations in chunks (CR removed, % escaped). Research helper only."""
import sys

path, title = sys.argv[1], sys.argv[2]
start = int(sys.argv[3]) if len(sys.argv) > 3 else 0
limit = int(sys.argv[4]) if len(sys.argv) > 4 else 9
text = open(path, encoding="utf-8", errors="replace").read().replace("\r", "")
chunks = [text[i:i + 3500] for i in range(0, len(text), 3500)]
for n, c in enumerate(chunks[start:start + limit], start=start + 1):
    body = c.replace("%", "%25").replace("\n", "%0A")
    print(f"::notice title={title} {n}/{len(chunks)}::{body}")
