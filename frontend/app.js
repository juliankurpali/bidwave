(() => {
  const STORAGE_KEY = "bidwave.visitor.v1";
  const NAME_COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000;
  const ADJECTIVES = [
    "Swift", "Bold", "Lucky", "Quiet", "Bright", "Wild", "Calm", "Rapid",
    "Noble", "Keen", "Merry", "Sharp", "Cosmic", "Neon", "Copper", "Silver",
  ];
  const ANIMALS = [
    "Otter", "Falcon", "Panda", "Fox", "Lynx", "Heron", "Koala", "Wolf",
    "Hawk", "Seal", "Tiger", "Crane", "Bear", "Moth", "Finch", "Drake",
  ];

  const statusEl = document.getElementById("status");
  const toastEl = document.getElementById("toast");
  const lotGrid = document.getElementById("lotGrid");
  const emptyHint = document.getElementById("emptyHint");
  const floorEl = document.querySelector(".floor");
  const paddleEl = document.getElementById("paddle");
  const youNameEl = document.getElementById("youName");
  const placeAsEl = document.getElementById("placeAs");
  const highAmountEl = document.getElementById("highAmount");
  const highBidderEl = document.getElementById("highBidder");
  const lotTitleEl = document.getElementById("lotTitle");
  const lotMetaEl = document.getElementById("lotMeta");
  const endsInEl = document.getElementById("endsIn");
  const lotStatusEl = document.getElementById("lotStatus");
  const liveTagEl = document.getElementById("liveTag");
  const apiHintEl = document.getElementById("apiHint");
  const placeBtn = document.getElementById("placeBtn");
  const amountInput = document.getElementById("amount");
  const bidListEl = document.getElementById("bidList");
  const bidCountEl = document.getElementById("bidCount");
  const hoodToggle = document.getElementById("hoodToggle");
  const hoodIntro = document.getElementById("hoodIntro");
  const archViz = document.getElementById("archViz");
  const archCallLabel = document.getElementById("archCallLabel");
  const hoodStepTitle = document.getElementById("hoodStepTitle");
  const hoodStepDesc = document.getElementById("hoodStepDesc");
  const hoodReplay = document.getElementById("hoodReplay");

  /** BidWave map — serverless always-on stack only (no ALB/EC2). */
  const ARCH = {
    zones: [
      { id: "edge", label: "Edge / entry", x: 8, y: 6, w: 584, h: 108 },
      { id: "compute", label: "API + Lambda", x: 8, y: 124, w: 584, h: 108 },
      { id: "data", label: "Async + data", x: 8, y: 242, w: 392, h: 168 },
      { id: "ops", label: "Ops", x: 412, y: 242, w: 180, h: 168 },
    ],
    nodes: {
      r53: { label: "Route 53", sub: "DNS · optional", x: 22, y: 34, w: 132, h: 52, icon: "route53", optional: true },
      cf: { label: "CloudFront", sub: "HTTPS CDN", x: 166, y: 34, w: 132, h: 52, icon: "cloudfront" },
      s3: { label: "S3", sub: "Static UI", x: 310, y: 34, w: 120, h: 52, icon: "s3" },
      browser: { label: "Browser", sub: "Your paddle", x: 444, y: 34, w: 132, h: 52, icon: "browser" },
      placeBid: { label: "PlaceBid", sub: "POST /bids", x: 22, y: 152, w: 142, h: 52, icon: "lambda" },
      apigw: { label: "API Gateway", sub: "HTTP API + key", x: 176, y: 152, w: 142, h: 52, icon: "apigateway" },
      listLots: { label: "ListLots", sub: "GET /lots", x: 330, y: 152, w: 128, h: 52, icon: "lambda" },
      listBids: { label: "ListBids", sub: "GET …/bids", x: 470, y: 152, w: 110, h: 52, icon: "lambda" },
      sqs: { label: "SQS FIFO", sub: "+ DLQ", x: 22, y: 274, w: 128, h: 52, icon: "sqs" },
      settle: { label: "SettleBid", sub: "SQS → worker", x: 164, y: 274, w: 140, h: 52, icon: "lambda" },
      ddbLots: { label: "DynamoDB Lots", sub: "highBid", x: 22, y: 340, w: 148, h: 52, icon: "dynamodb" },
      ddbHist: { label: "BidHistory", sub: "audit trail", x: 184, y: 340, w: 132, h: 52, icon: "dynamodb" },
      iam: { label: "IAM", sub: "Least privilege", x: 430, y: 274, w: 144, h: 52, icon: "iam" },
      cw: { label: "CloudWatch", sub: "Logs · alarms", x: 430, y: 340, w: 144, h: 52, icon: "cloudwatch" },
    },
    edges: [
      ["browser", "cf"],
      ["r53", "cf"],
      ["cf", "s3"],
      ["browser", "apigw"],
      ["apigw", "placeBid"],
      ["apigw", "listLots"],
      ["apigw", "listBids"],
      ["placeBid", "sqs"],
      ["placeBid", "cw"],
      ["sqs", "settle"],
      ["settle", "ddbLots"],
      ["settle", "ddbHist"],
      ["settle", "cw"],
      ["listLots", "ddbLots"],
      ["listLots", "cw"],
      ["listBids", "ddbHist"],
      ["listBids", "cw"],
      ["iam", "placeBid"],
      ["iam", "settle"],
    ],
    calls: {
      // waves = parallel edge groups. Multiple edges in one wave = multiple pulse trackers (XP circles).
      placeBid: {
        title: "Place bid · POST /bids",
        blurb: "Fan-outs spawn multiple trackers (e.g. SettleBid → Lots + History + CloudWatch).",
        extra: ["iam"],
        waves: [
          { from: "browser", label: "Browser sends POST /bids", edges: [["browser", "apigw"]] },
          { from: "apigw", label: "API Gateway routes to PlaceBid", edges: [["apigw", "placeBid"]] },
          {
            from: "placeBid",
            label: "PlaceBid enqueues SQS and logs to CloudWatch",
            edges: [["placeBid", "sqs"], ["placeBid", "cw"]],
          },
          { from: "sqs", label: "SQS wakes SettleBid", edges: [["sqs", "settle"]] },
          {
            from: "settle",
            label: "SettleBid writes Lots + History and logs",
            edges: [["settle", "ddbLots"], ["settle", "ddbHist"], ["settle", "cw"]],
          },
        ],
        details: {
          browser: "You submit a bid from the paddle.",
          apigw: "API Gateway authenticates (API key) and routes POST /bids.",
          placeBid: "PlaceBid validates, then calls SQS and CloudWatch together.",
          sqs: "SQS FIFO buffers the storm; group = lotId.",
          settle: "SettleBid fans out to DynamoDB Lots, BidHistory, and CloudWatch.",
          ddbLots: "Conditional update changes highBid only when allowed.",
          ddbHist: "BidHistory stores ACCEPTED / REJECTED / DUPLICATE.",
          cw: "CloudWatch keeps the proof for your demo.",
          iam: "Least-privilege roles authorize each hop.",
        },
      },
      listLots: {
        title: "List lots · GET /lots",
        blurb: "ListLots reads DynamoDB and logs — two trackers leave the Lambda.",
        extra: ["cf", "s3"],
        waves: [
          { from: "browser", label: "Browser asks for lots", edges: [["browser", "apigw"]] },
          { from: "apigw", label: "API Gateway → ListLots", edges: [["apigw", "listLots"]] },
          {
            from: "listLots",
            label: "ListLots reads DynamoDB and logs",
            edges: [["listLots", "ddbLots"], ["listLots", "cw"]],
          },
        ],
        details: {
          browser: "Home page asks for live lots.",
          apigw: "GET /lots hits API Gateway.",
          listLots: "ListLots fans out to DynamoDB Lots + CloudWatch.",
          ddbLots: "Lots table returns titles and high bids.",
          cw: "The read is logged like every other call.",
        },
      },
      listBids: {
        title: "List bidders · GET …/bids",
        blurb: "ListBids queries history and logs in parallel.",
        waves: [
          { from: "browser", label: "Browser refreshes bidders", edges: [["browser", "apigw"]] },
          { from: "apigw", label: "API Gateway → ListBids", edges: [["apigw", "listBids"]] },
          {
            from: "listBids",
            label: "ListBids reads BidHistory and logs",
            edges: [["listBids", "ddbHist"], ["listBids", "cw"]],
          },
        ],
        details: {
          browser: "Bidder list refresh (chip or replay — not background poll).",
          apigw: "Routes GET /lots/{id}/bids.",
          listBids: "Fans out to BidHistory + CloudWatch.",
          ddbHist: "Returns who bid and whether it stuck.",
          cw: "Observability on the read path.",
        },
      },
    },
  };

  let apiBase = "";
  let apiKey = "";
  let requireApiKey = true;
  let mode = "aws";
  let visitor = null;
  let lots = [];
  let selectedLotId = null;
  let currentLot = null;
  let lastHigh = null;
  let pollTimer = null;
  let clockTimer = null;
  let lastJourney = null;
  let hoodAnimTimer = null;
  let hoodPlayToken = 0;
  let liveMode = false;
  let liveFlashToken = 0;
  let liveIdleTimer = null;
  const nodeCenters = {};

  function diag(msg) {
    statusEl.textContent = typeof msg === "string" ? msg : JSON.stringify(msg, null, 2);
  }

  function toast(message, isError = false) {
    toastEl.hidden = false;
    toastEl.textContent = message;
    toastEl.classList.toggle("is-error", isError);
  }

  function money(n) {
    const value = Number(n);
    if (Number.isNaN(value)) return "—";
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: "USD",
      minimumFractionDigits: 0,
      maximumFractionDigits: 2,
    }).format(value);
  }

  function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  function randomHandle() {
    const a = ADJECTIVES[Math.floor(Math.random() * ADJECTIVES.length)];
    const b = ANIMALS[Math.floor(Math.random() * ANIMALS.length)];
    return `${a} ${b}`;
  }

  function handleChangedAt(v) {
    return Number(v.handleChangedAt || v.createdAt || 0);
  }

  function msUntilNameChange(v) {
    return Math.max(0, NAME_COOLDOWN_MS - (Date.now() - handleChangedAt(v)));
  }

  function formatCooldown(ms) {
    const days = Math.ceil(ms / (24 * 60 * 60 * 1000));
    if (days <= 1) return "available in under a day";
    return `available in ${days} days`;
  }

  function persistVisitor() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(visitor));
  }

  function loadOrCreateVisitor() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && parsed.id && parsed.handle) {
          visitor = parsed;
          if (!visitor.handleChangedAt) {
            visitor.handleChangedAt = visitor.createdAt || Date.now();
            persistVisitor();
          }
          paintVisitor();
          return visitor;
        }
      }
    } catch (_) {
      /* recreate */
    }
    const now = Date.now();
    visitor = { id: uuid(), handle: randomHandle(), createdAt: now, handleChangedAt: now };
    persistVisitor();
    paintVisitor();
    return visitor;
  }

  function tryChangeName() {
    if (!visitor) loadOrCreateVisitor();
    const wait = msUntilNameChange(visitor);
    if (wait > 0) {
      toast(`Name locked — ${formatCooldown(wait)}.`, true);
      paintVisitor();
      return;
    }
    const next = randomHandle();
    const ok = window.confirm(
      `Change your paddle name?\n\nCurrent: ${visitor.handle}\nNew: ${next}\n\nYou won’t be able to change it again for 7 days.`
    );
    if (!ok) {
      toast("Name change cancelled.");
      return;
    }
    visitor.handle = next;
    visitor.handleChangedAt = Date.now();
    persistVisitor();
    paintVisitor();
    toast(`Now bidding as ${visitor.handle}`);
  }

  function paintVisitor() {
    youNameEl.textContent = visitor.handle;
    placeAsEl.textContent = visitor.handle;
    const btn = document.getElementById("reshuffleBtn");
    if (!btn) return;
    const wait = msUntilNameChange(visitor);
    if (wait > 0) {
      btn.disabled = true;
      btn.textContent = formatCooldown(wait);
      btn.title = "You can pick a new paddle name once per week";
    } else {
      btn.disabled = false;
      btn.textContent = "New name";
      btn.title = "Change paddle name (asks to confirm; once per week)";
    }
  }

  function formatRemainingHm(endsAtIso) {
    const endsAt = Date.parse(endsAtIso);
    if (Number.isNaN(endsAt)) return "—";
    const diff = endsAt - Date.now();
    if (diff <= 0) return "Ended";
    const totalMin = Math.floor(diff / 60000);
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    if (h <= 0) return `${m}m left`;
    return `${h}h ${m}m left`;
  }

  function isClosed(lot) {
    if (!lot || !lot.endsAt) return false;
    return Date.now() >= Date.parse(lot.endsAt);
  }

  function tickClock() {
    if (!currentLot) return;
    endsInEl.textContent = formatRemainingHm(currentLot.endsAt);
    const closed = isClosed(currentLot);
    lotStatusEl.textContent = closed ? "Closed" : "Open";
    liveTagEl.textContent = closed ? "Closed" : "Open";
  }

  function refreshFloorTimes() {
    if (!paddleEl.hidden) return;
    lotGrid.querySelectorAll("[data-ends-at]").forEach((el) => {
      el.textContent = formatRemainingHm(el.getAttribute("data-ends-at"));
    });
  }

  function suggestAmount(lot) {
    return Math.round(((Number(lot.highBid) || 0) + 1) * 100) / 100;
  }

  function renderLotDetail(lot) {
    currentLot = lot;
    selectedLotId = lot.lotId;
    lotTitleEl.textContent = lot.title || lot.lotId;
    lotMetaEl.textContent = `Lot ${lot.lotId}`;
    paintHighBid(lot.highBid, lot.highBidder);
    amountInput.value = String(suggestAmount(lot));
    tickClock();
  }

  function paintHighBid(highBid, highBidder) {
    const high = Number(highBid);
    highAmountEl.textContent = money(high);
    lastHigh = high;
    highBidderEl.textContent =
      highBidder && highBidder !== "Auctioneer"
        ? `Leading: ${highBidder}`
        : "No guest bids yet — open it up";
    if (currentLot) {
      currentLot.highBid = high;
      currentLot.highBidder = highBidder;
    }
  }


  function renderBids(bids) {
    bidListEl.innerHTML = "";
    const list = Array.isArray(bids) ? bids : [];
    bidCountEl.textContent = String(list.length);
    if (!list.length) {
      const empty = document.createElement("li");
      empty.className = "bid-empty";
      empty.textContent = "No bids yet — be first.";
      bidListEl.appendChild(empty);
      return;
    }
    for (const bid of list) {
      const li = document.createElement("li");
      const accepted = bid.status === "ACCEPTED";
      li.className = accepted ? "is-accepted" : "is-rejected";
      const name = document.createElement("span");
      name.className = "bid-name";
      name.textContent = bid.bidder || "—";
      const amt = document.createElement("span");
      amt.className = "bid-amount";
      amt.textContent = money(bid.amount);
      const st = document.createElement("span");
      st.className = "bid-status";
      st.textContent = accepted ? "Accepted" : String(bid.status || "").replace(/_/g, " ").toLowerCase();
      li.append(name, amt, st);
      bidListEl.appendChild(li);
    }
  }

  function edgeKey(a, b) {
    return `${a}->${b}`;
  }

  function wirePath(a, b) {
    const from = nodeCenters[a];
    const to = nodeCenters[b];
    if (!from || !to) return "";
    const mx = (from.x + to.x) / 2;
    const my = (from.y + to.y) / 2;
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const bend = Math.min(36, Math.hypot(dx, dy) * 0.2);
    const cx = mx + (dy === 0 ? 0 : bend * (dx > 0 ? 0.15 : -0.15));
    const cy = my - bend * 0.35;
    return `M ${from.x} ${from.y} Q ${cx} ${cy} ${to.x} ${to.y}`;
  }

  function setActiveChip(pathId) {
    document.querySelectorAll(".path-chip").forEach((btn) => {
      btn.classList.toggle("is-active", btn.dataset.path === pathId);
    });
  }

  function clearLivePaint() {
    archViz.querySelectorAll(".arch-wire").forEach((w) => {
      w.classList.remove("is-lit", "is-live-pulse");
    });
    archViz.querySelectorAll(".arch-node").forEach((n) => {
      n.classList.remove("is-active", "is-done", "is-dim", "is-live-hot");
    });
    clearPulses();
  }

  function paintLiveListening(extraDesc) {
    setActiveChip("live");
    archCallLabel.textContent = "Live · listening";
    hoodStepTitle.textContent = "Live traffic";
    hoodStepDesc.textContent =
      extraDesc || "Watching your API calls. Place a bid to light the write path.";
  }

  function enterLiveMode() {
    liveMode = true;
    hoodPlayToken += 1; // cancel any in-flight canned animation
    clearLivePaint();
    hoodReplay.disabled = true;
    paintLiveListening();
  }

  function exitLiveMode() {
    liveMode = false;
    if (liveIdleTimer) {
      clearTimeout(liveIdleTimer);
      liveIdleTimer = null;
    }
  }

  /** Brief real-time highlight for an ARCH.calls path (does not leave Live mode). */
  function flashLiveEvent(callId, meta = {}) {
    if (!liveMode) return;
    const call = ARCH.calls[callId];
    if (!call) return;
    const token = ++liveFlashToken;
    const { nodes, edges } = callNodesAndEdges(call);

    clearLivePaint();
    dimAllExcept(nodes);
    edges.forEach((e) => {
      const w = archViz.querySelector(`[data-edge="${e}"]`);
      if (w) {
        w.classList.add("is-lit", "is-live-pulse");
      }
    });
    nodes.forEach((id) => {
      archViz.querySelector(`[data-node="${id}"]`)?.classList.add("is-live-hot", "is-done");
    });

    // Parallel XP pulses on all lit edges
    edges.forEach((e) => {
      const pulse = spawnPulse();
      if (pulse) {
        movePulseAlongEdge(e, 520, pulse).then(() => pulse.remove());
      }
    });

    setActiveChip("live");
    archCallLabel.textContent = `Live · ${meta.title || call.title}`;
    hoodStepTitle.textContent = meta.title || call.title;
    hoodStepDesc.textContent = meta.desc || call.blurb;
    lastJourney = { callId, meta };

    if (liveIdleTimer) clearTimeout(liveIdleTimer);
    liveIdleTimer = setTimeout(() => {
      if (!liveMode || token !== liveFlashToken) return;
      clearLivePaint();
      paintLiveListening();
    }, 3200);
  }

  function buildArchViz() {
    const NS = "http://www.w3.org/2000/svg";
    const XLINK = "http://www.w3.org/1999/xlink";
    const ICON_BASE = "assets/aws-icons";
    const w = 600;
    const h = 430;
    Object.keys(nodeCenters).forEach((k) => delete nodeCenters[k]);

    archViz.innerHTML = "";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "BidWave serverless architecture — CloudFront, API Gateway, Lambda, SQS, DynamoDB");

    ARCH.zones.forEach((z) => {
      const g = document.createElementNS(NS, "g");
      const rect = document.createElementNS(NS, "rect");
      rect.setAttribute("class", "arch-zone" + (z.id === "demo" ? " is-demo" : ""));
      rect.setAttribute("x", String(z.x));
      rect.setAttribute("y", String(z.y));
      rect.setAttribute("width", String(z.w));
      rect.setAttribute("height", String(z.h));
      rect.setAttribute("rx", "14");
      g.appendChild(rect);
      const label = document.createElementNS(NS, "text");
      label.setAttribute("class", "arch-zone-label" + (z.id === "demo" ? " is-demo" : ""));
      label.setAttribute("x", String(z.x + 14));
      label.setAttribute("y", String(z.y + 18));
      label.textContent = z.label;
      g.appendChild(label);
      svg.appendChild(g);
    });

    Object.entries(ARCH.nodes).forEach(([id, n]) => {
      nodeCenters[id] = { x: n.x + n.w / 2, y: n.y + n.h / 2 };
    });

    ARCH.edges.forEach(([a, b]) => {
      const path = document.createElementNS(NS, "path");
      path.setAttribute("d", wirePath(a, b));
      path.setAttribute("class", "arch-wire");
      path.dataset.edge = edgeKey(a, b);
      svg.appendChild(path);
    });

    Object.entries(ARCH.nodes).forEach(([id, n]) => {
      const g = document.createElementNS(NS, "g");
      g.setAttribute("class", "arch-node" + (n.optional ? " is-optional" : ""));
      g.dataset.node = id;

      const rect = document.createElementNS(NS, "rect");
      rect.setAttribute("x", String(n.x));
      rect.setAttribute("y", String(n.y));
      rect.setAttribute("width", String(n.w));
      rect.setAttribute("height", String(n.h));
      rect.setAttribute("rx", "10");
      g.appendChild(rect);

      // Classic AWS Architecture Icons (Q1 2021 / Q1 2022) — not the post-2024 flat set
      const iconSize = 32;
      const iconX = n.x + 9;
      const iconY = n.y + (n.h - iconSize) / 2;
      if (n.icon) {
        const img = document.createElementNS(NS, "image");
        const href = `${ICON_BASE}/${n.icon}.svg`;
        img.setAttributeNS(XLINK, "href", href);
        img.setAttribute("href", href);
        img.setAttribute("x", String(iconX));
        img.setAttribute("y", String(iconY));
        img.setAttribute("width", String(iconSize));
        img.setAttribute("height", String(iconSize));
        img.setAttribute("preserveAspectRatio", "xMidYMid meet");
        g.appendChild(img);
      }

      const textX = n.x + (n.icon ? 48 : n.w / 2);
      const anchor = n.icon ? "start" : "middle";

      const title = document.createElementNS(NS, "text");
      title.setAttribute("class", "arch-title");
      title.setAttribute("x", String(textX));
      title.setAttribute("y", String(n.y + 22));
      title.setAttribute("text-anchor", anchor);
      title.textContent = n.label;
      g.appendChild(title);

      const sub = document.createElementNS(NS, "text");
      sub.setAttribute("class", "arch-sub");
      sub.setAttribute("x", String(textX));
      sub.setAttribute("y", String(n.y + 38));
      sub.setAttribute("text-anchor", anchor);
      sub.textContent = n.sub;
      g.appendChild(sub);

      svg.appendChild(g);
    });

    archViz.appendChild(svg);
    showIdleArchitecture();
  }

  function clearPulses() {
    archViz.querySelectorAll(".arch-pulse").forEach((p) => p.remove());
  }

  function showIdleArchitecture() {
    exitLiveMode();
    setActiveChip("idle");
    archViz.querySelectorAll(".arch-node").forEach((n) => {
      n.classList.remove("is-active", "is-done", "is-dim", "is-live-hot");
    });
    archViz.querySelectorAll(".arch-wire").forEach((w) => {
      w.classList.remove("is-lit", "is-dim", "is-live-pulse");
    });
    clearPulses();
    archCallLabel.textContent = "Showing full architecture";
    hoodStepTitle.textContent = "Full BidWave map";
    hoodStepDesc.textContent =
      "Tap Live to watch real traffic, or a path chip to rehearse Place bid / List lots / List bidders.";
  }

  function dimAllExcept(activeNodes) {
    const nodeSet = new Set(activeNodes);
    archViz.querySelectorAll(".arch-node").forEach((n) => {
      const id = n.dataset.node;
      n.classList.toggle("is-dim", !nodeSet.has(id));
      n.classList.remove("is-active", "is-done");
    });
    // Wires stay invisible until each hop lights them during animation
    archViz.querySelectorAll(".arch-wire").forEach((w) => {
      w.classList.remove("is-lit");
    });
  }

  function callNodesAndEdges(call) {
    const nodes = new Set(call.extra || []);
    const edges = [];
    (call.waves || []).forEach((wave) => {
      nodes.add(wave.from);
      (wave.edges || []).forEach(([a, b]) => {
        nodes.add(a);
        nodes.add(b);
        edges.push(edgeKey(a, b));
      });
    });
    return {
      nodes: [...nodes].filter((id) => ARCH.nodes[id]),
      edges: edges.filter((e) =>
        ARCH.edges.some(([a, b]) => edgeKey(a, b) === e)
      ),
    };
  }

  function movePulseAlongEdge(edgeId, durationMs, pulseEl) {
    return new Promise((resolve) => {
      const wire = archViz.querySelector(`[data-edge="${edgeId}"]`);
      if (!wire || !pulseEl) {
        resolve();
        return;
      }
      pulseEl.classList.add("is-on");
      const len = wire.getTotalLength();
      const t0 = performance.now();
      let raf = 0;
      const step = (now) => {
        const p = Math.min(1, (now - t0) / durationMs);
        const ease = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
        const pt = wire.getPointAtLength(len * ease);
        pulseEl.setAttribute("cx", String(pt.x));
        pulseEl.setAttribute("cy", String(pt.y));
        if (p < 1) raf = requestAnimationFrame(step);
        else resolve();
      };
      raf = requestAnimationFrame(step);
      // stash for cancel on replay
      pulseEl._raf = raf;
    });
  }

  function spawnPulse() {
    const NS = "http://www.w3.org/2000/svg";
    const svg = archViz.querySelector("svg");
    if (!svg) return null;
    const pulse = document.createElementNS(NS, "circle");
    pulse.setAttribute("r", "6");
    pulse.setAttribute("class", "arch-pulse is-on");
    svg.appendChild(pulse);
    return pulse;
  }

  async function playCallPath(callId, meta = {}) {
    if (callId === "idle") {
      showIdleArchitecture();
      return;
    }
    if (callId === "live") {
      enterLiveMode();
      return;
    }
    exitLiveMode();
    const call = ARCH.calls[callId];
    if (!call || !call.waves) return;

    clearPulses();
    const token = ++hoodPlayToken;
    lastJourney = { callId, meta };
    hoodReplay.disabled = false;
    setActiveChip(callId);

    const { nodes: highlightNodes, edges: allEdges } = callNodesAndEdges(call);

    archCallLabel.textContent = `Tracing · ${call.title}`;
    hoodIntro.textContent =
      mode === "local" ? `${call.blurb} (local demo — AWS path shown)` : call.blurb;

    dimAllExcept(highlightNodes);

    if (!hoodToggle.checked) {
      allEdges.forEach((e) => archViz.querySelector(`[data-edge="${e}"]`)?.classList.add("is-lit"));
      highlightNodes.forEach((id) => archViz.querySelector(`[data-node="${id}"]`)?.classList.add("is-done"));
      hoodStepTitle.textContent = call.title;
      hoodStepDesc.textContent = "Path held open — tap Full map to clear, or another chip to re-trace.";
      return;
    }

    for (let w = 0; w < call.waves.length; w++) {
      if (token !== hoodPlayToken) return;
      const wave = call.waves[w];
      const fromEl = archViz.querySelector(`[data-node="${wave.from}"]`);
      if (fromEl) {
        fromEl.classList.add("is-active");
        fromEl.classList.remove("is-done");
      }
      hoodStepTitle.textContent = `${w + 1}/${call.waves.length} · ${ARCH.nodes[wave.from]?.label || wave.from}`;
      hoodStepDesc.textContent =
        wave.label ||
        call.details[wave.from] ||
        `${wave.edges.length} tracker${wave.edges.length > 1 ? "s" : ""} leaving this service`;

      await new Promise((r) => setTimeout(r, 180));
      if (token !== hoodPlayToken) return;

      const edgeIds = wave.edges
        .map(([a, b]) => edgeKey(a, b))
        .filter((e) => allEdges.includes(e));
      edgeIds.forEach((e) => archViz.querySelector(`[data-edge="${e}"]`)?.classList.add("is-lit"));

      // One XP circle per outbound call — fan-out = parallel trackers
      const pulses = edgeIds.map((e) => {
        const pulse = spawnPulse();
        return movePulseAlongEdge(e, 480, pulse).then(() => {
          if (pulse) pulse.remove();
        });
      });
      await Promise.all(pulses);
      if (token !== hoodPlayToken) return;

      if (fromEl) {
        fromEl.classList.remove("is-active");
        fromEl.classList.add("is-done");
      }
      wave.edges.forEach(([, b]) => {
        const toEl = archViz.querySelector(`[data-node="${b}"]`);
        if (toEl) toEl.classList.add("is-done");
      });
    }

    if (token !== hoodPlayToken) return;
    clearPulses();
    // Leave path wires lit — don't snap back to empty map
    allEdges.forEach((e) => archViz.querySelector(`[data-edge="${e}"]`)?.classList.add("is-lit"));
    highlightNodes.forEach((id) => {
      const el = archViz.querySelector(`[data-node="${id}"]`);
      if (el) {
        el.classList.remove("is-active", "is-dim");
        el.classList.add("is-done");
      }
    });
    const outcome = meta.status ? ` · ${meta.status}` : "";
    hoodStepTitle.textContent = `Done${outcome}`;
    hoodStepDesc.textContent = `${call.title} — connections held open. Tap Full map to clear.`;
    archCallLabel.textContent = `Held · ${call.title}`;
  }

  async function refreshBids(opts = {}) {
    if (!selectedLotId) return;
    if (mode !== "local" && !apiBase) return;
    const res = await fetch(`${apiBase}/lots/${encodeURIComponent(selectedLotId)}/bids`);
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      diag({ action: "GET bids", status: res.status, body });
      return;
    }
    renderBids(body.bids || []);
    // Live mode: flash on real GET …/bids (poll + open lot). skipLiveFlash keeps Place bid visible.
    if (liveMode && !opts.skipLiveFlash) {
      flashLiveEvent("listBids", {
        title: "List bidders · live",
        desc: `GET /lots/${selectedLotId}/bids returned ${(body.bids || []).length} rows`,
      });
    }
  }

  function showFloor() {
    paddleEl.hidden = true;
    floorEl.hidden = false;
    selectedLotId = null;
    currentLot = null;
    renderBids([]);
  }

  function showPaddle(lot) {
    floorEl.hidden = true;
    paddleEl.hidden = false;
    renderLotDetail(lot);
    // Don't kill Live when opening a lot — only reset the map if not listening.
    if (liveMode) {
      paintLiveListening("Watching your API calls. Opening a lot loads bidders (listBids).");
    } else {
      showIdleArchitecture();
    }
    refreshBids().catch(() => {});
  }

  function renderLotGrid() {
    lotGrid.innerHTML = "";
    if (!lots.length) {
      emptyHint.hidden = false;
      emptyHint.textContent = apiBase || mode === "local"
        ? "No lots yet — seed auctions after deploy."
        : "Deploy BidWave, then open this page from CloudFront.";
      lotGrid.appendChild(emptyHint);
      return;
    }
    emptyHint.hidden = true;
    const myHandle = visitor && visitor.handle;
    for (const lot of lots) {
      const btn = document.createElement("button");
      btn.type = "button";
      const leading = myHandle && lot.highBidder === myHandle;
      btn.className = "lot-pick" + (leading ? " is-leading" : "");
      btn.innerHTML = `
        <span class="lot-pick-title"></span>
        <span class="lot-pick-bid"></span>
        <span class="lot-pick-meta"></span>
        <span class="lot-pick-time" data-ends-at=""></span>
      `;
      btn.querySelector(".lot-pick-title").textContent = lot.title || lot.lotId;
      btn.querySelector(".lot-pick-bid").textContent = money(lot.highBid);
      const timeEl = btn.querySelector(".lot-pick-time");
      timeEl.setAttribute("data-ends-at", lot.endsAt || "");
      timeEl.textContent = isClosed(lot) ? "Ended" : formatRemainingHm(lot.endsAt);
      if (isClosed(lot)) {
        btn.querySelector(".lot-pick-meta").textContent = leading ? "Closed · you won" : "Closed";
      } else if (leading) {
        btn.querySelector(".lot-pick-meta").textContent = "You’re leading";
      } else {
        btn.querySelector(".lot-pick-meta").textContent = `Leading: ${lot.highBidder || "—"}`;
      }
      btn.addEventListener("click", () => showPaddle(lot));
      lotGrid.appendChild(btn);
    }
  }

  async function loadConfig() {
    try {
      const res = await fetch("config.json", { cache: "no-store" });
      if (res.ok) {
        const cfg = await res.json();
        mode = cfg.mode || "aws";
        apiBase = (cfg.apiBaseUrl || "").replace(/\/$/, "");
        requireApiKey = cfg.requireApiKey !== false && mode !== "local";
        apiKey = cfg.apiKey || "";
        if (apiKey) document.getElementById("apiKey").value = apiKey;
        apiHintEl.textContent = mode === "local"
          ? "Local mode · no AWS · no secrets"
          : apiBase ? `AWS · ${apiBase}` : "API not configured";
        diag({ mode, apiBase: apiBase || "(same-origin)", requireApiKey });
        return;
      }
    } catch (_) {
      /* file open */
    }
    apiHintEl.textContent = "Run ./scripts/run-local.sh then open http://127.0.0.1:8080/";
    diag("No config.json — start the local server");
  }

  async function refreshLots(opts = {}) {
    if (mode !== "local" && !apiBase) {
      renderLotGrid();
      return;
    }
    const res = await fetch(`${apiBase}/lots`);
    const body = await res.json().catch(() => ({}));
    diag({ action: "GET /lots", status: res.status, body });
    if (!res.ok) {
      toast(body.error || `List failed (${res.status})`, true);
      return;
    }
    lots = Array.isArray(body.lots) ? body.lots : [];
    renderLotGrid();
    if (selectedLotId) {
      const updated = lots.find((l) => l.lotId === selectedLotId);
      if (updated) {
        // Don't clobber amount input every 4s — only update high via paint when not typing
        if (paddleEl && !paddleEl.hidden) {
          paintHighBid(updated.highBid, updated.highBidder);
          currentLot = updated;
        } else {
          renderLotDetail(updated);
        }
      }
      await refreshBids({ skipLiveFlash: opts.skipLiveFlash });
    }
    // Live: flash listLots only on explicit user refresh (poll stays quiet on home).
    if (liveMode && !opts.skipLiveFlash && opts.fromUser) {
      flashLiveEvent("listLots", {
        title: "List lots · live",
        desc: `GET /lots returned ${lots.length} lots`,
      });
    }
  }

  async function placeBid() {
    if (!visitor) loadOrCreateVisitor();
    if (mode !== "local" && !apiBase) {
      toast("No API URL — run locally with ./scripts/run-local.sh or deploy to AWS.", true);
      return;
    }
    const key = document.getElementById("apiKey").value.trim() || apiKey;
    if (requireApiKey && !key) {
      toast("Missing API key (gitignored config.json after deploy).", true);
      return;
    }
    if (!selectedLotId || !currentLot) {
      toast("Pick a lot first.", true);
      return;
    }
    if (isClosed(currentLot)) {
      toast("This lot is closed.", true);
      return;
    }
    const amount = Number(amountInput.value);
    if (!(amount > 0)) {
      toast("Enter a positive bid.", true);
      return;
    }

    placeBtn.disabled = true;
    try {
      const headers = { "content-type": "application/json" };
      if (requireApiKey && key) headers["x-api-key"] = key;
      const res = await fetch(`${apiBase}/bids`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          lotId: selectedLotId,
          bidder: visitor.handle,
          amount,
          correlationId: visitor.id,
        }),
      });
      const body = await res.json().catch(() => ({}));
      diag({ action: "POST /bids", status: res.status, body });
      if (!res.ok) {
        toast(body.error || body.message || `Bid failed (${res.status})`, true);
        return;
      }
      toast(`${visitor.handle} bid ${money(amount)}`);
      amountInput.value = String(Math.round((amount + 1) * 100) / 100);
      if (liveMode) {
        flashLiveEvent("placeBid", {
          title: "Place bid · live",
          desc: `POST /bids queued ${money(amount)} — SettleBid will update DynamoDB next`,
        });
        // Refresh data without stealing the Place bid flash (listBids will show on next poll).
        await refreshLots({ skipLiveFlash: true });
      } else {
        await playCallPath("placeBid", {
          status: body.status || "QUEUED",
          amount,
          bidder: visitor.handle,
        });
        await refreshLots();
      }
    } finally {
      placeBtn.disabled = false;
    }
  }

  document.getElementById("bidForm").addEventListener("submit", (e) => {
    e.preventDefault();
    placeBid().catch((err) => toast(String(err), true));
  });

  document.getElementById("quickBids").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-delta]");
    if (!btn || !currentLot) return;
    const delta = Number(btn.getAttribute("data-delta"));
    amountInput.value = String(Math.round(((Number(currentLot.highBid) || 0) + delta) * 100) / 100);
    amountInput.focus();
  });

  document.getElementById("backBtn").addEventListener("click", () => {
    showFloor();
    refreshLots().catch(() => {});
  });

  document.getElementById("reshuffleBtn").addEventListener("click", () => tryChangeName());

  document.getElementById("brandHome").addEventListener("click", (e) => {
    e.preventDefault();
    showFloor();
    refreshLots().catch(() => {});
    window.scrollTo({ top: 0, behavior: "smooth" });
  });

  buildArchViz();

  document.getElementById("pathChips").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-path]");
    if (!chip) return;
    const path = chip.dataset.path;
    playCallPath(path).catch(() => {});
  });

  hoodToggle.addEventListener("change", () => {
    if (liveMode) {
      paintLiveListening(
        hoodToggle.checked
          ? "Live mode — pulses on for real events."
          : "Live mode — wires still light on real events (pulses off)."
      );
      return;
    }
    if (!hoodToggle.checked) {
      hoodStepDesc.textContent = "Animate off — path chips still highlight without the pulse.";
    } else {
      showIdleArchitecture();
    }
  });
  hoodReplay.addEventListener("click", () => {
    if (liveMode) {
      paintLiveListening("Replay is for canned paths — stay on Live to watch real traffic.");
      return;
    }
    if (lastJourney) playCallPath(lastJourney.callId, lastJourney.meta || {}).catch(() => {});
  });

  loadOrCreateVisitor();
  loadConfig().then(() => {
    refreshLots().catch((e) => toast(String(e), true));
    pollTimer = setInterval(() => refreshLots().catch(() => {}), 4000);
    clockTimer = setInterval(() => {
      tickClock();
      refreshFloorTimes();
    }, 30_000);
    setInterval(() => {
      if (visitor) paintVisitor();
    }, 60_000);
  });

  window.addEventListener("beforeunload", () => {
    if (pollTimer) clearInterval(pollTimer);
    if (clockTimer) clearInterval(clockTimer);
    if (hoodAnimTimer) cancelAnimationFrame(hoodAnimTimer);
  });
})();
