    // dsh-mobile safe keeps shipped plugins (S1): 只摘第三方 insert 子条目，保留我方装配的插件
    // 与全部顶层 disable 行（与壳侧 SafeMode.kt 同口径）。原实现整份覆写成最小文件，会摘掉
    // 12 个 @dsh-android/* 引用与 7 条 disabled（含安全关键的 client-hmr）。
    const dshMobileSafeShippedPrefixes = ["@deepseek-ai/", "@dsh-android/"];
    const dshMobileSafeShippedNames = ["dsh-undo-savepoint", "dshmarketplace-plugin"];
    const dshMobileSafeIsShipped = (name) => {
      const v = String(name ?? "").trim().replace(/^['\"]+|['\"]+$/g, "");
      if (v === "") return false;
      if (dshMobileSafeShippedNames.includes(v)) return true;
      return dshMobileSafeShippedPrefixes.some((p) => v.startsWith(p));
    };
    const dshMobileSafeFilterInserts = (text) => {
      const lines = String(text).split("\n");
      const out = [];
      let i = 0;
      while (i < lines.length) {
        if (!/^- insert:\s*$/.test(lines[i])) { out.push(lines[i]); i += 1; continue; }
        let end = i + 1;
        while (end < lines.length && !/^-/.test(lines[end])) end += 1;
        const body = lines.slice(i + 1, end);
        const firstItem = body.find((l) => /^(\s*)-\s+(id|name):/.test(l));
        const itemIndent = firstItem ? firstItem.length - firstItem.replace(/^\s+/, "").length : null;
        if (itemIndent === null) { out.push(lines[i]); out.push(...body); i = end; continue; }
        // 按缩进切子条目块
        const chunks = [];
        let cur = null;
        for (const line of body) {
          const m = /^(\s*)-\s+/.exec(line);
          if (m && m[1].length === itemIndent) { if (cur) chunks.push(cur); cur = [line]; }
          else if (cur) cur.push(line);
        }
        if (cur) chunks.push(cur);
        const kept = [];
        for (const chunk of chunks) {
          const nm = /^\s*-?\s*name:\s*['\"]?([^'\"\s]+)/m.exec(chunk.join("\n"));
          if (nm && !dshMobileSafeIsShipped(nm[1])) continue; // 第三方：整条摘掉
          kept.push(...chunk);
        }
        if (kept.length === 0) { i = end; continue; } // 空 insert 会让引擎 boot 抛
        out.push(lines[i]); out.push(...kept); i = end;
      }
      return out.join("\n");
    };
    const dshMobileSafePatchText = await fs.readFile(patch, 'utf8');
    const minimal = dshMobileSafeFilterInserts(dshMobileSafePatchText);
    await fs.writeFile(patch, minimal, 'utf8');
