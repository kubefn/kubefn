/**
 * Function loader — dynamically loads JS modules from a directory,
 * discovers exported handler functions, and registers routes.
 */

const fs = require('fs');
const path = require('path');

class FunctionLoader {
  constructor(functionsDir, heap) {
    this.functionsDir = functionsDir;
    this.heap = heap;
    this.loadedGroups = {};
    this.routes = new Map(); // path -> { handler, group, name, methods }
  }

  loadAll() {
    if (!fs.existsSync(this.functionsDir)) {
      fs.mkdirSync(this.functionsDir, { recursive: true });
      console.log(`Created functions directory: ${this.functionsDir}`);
      return;
    }

    const groups = fs.readdirSync(this.functionsDir, { withFileTypes: true })
      .filter(d => d.isDirectory() && !d.name.startsWith('.'));

    for (const group of groups) {
      this.loadGroup(group.name);
    }

    const totalRoutes = Object.values(this.loadedGroups)
      .reduce((sum, fns) => sum + fns.length, 0);
    console.log(`Loaded ${Object.keys(this.loadedGroups).length} groups with ${totalRoutes} functions`);
  }

  loadGroup(groupName) {
    const groupDir = path.join(this.functionsDir, groupName);
    console.log(`Loading function group: ${groupName} from ${groupDir}`);

    // Unload existing
    this.unloadGroup(groupName);

    const functions = [];
    const jsFiles = fs.readdirSync(groupDir)
      .filter(f => f.endsWith('.js') && !f.startsWith('_'));

    for (const jsFile of jsFiles) {
      const filePath = path.join(groupDir, jsFile);

      try {
        // Clear require cache for hot-reload
        delete require.cache[require.resolve(filePath)];

        const mod = require(filePath);

        // Look for exported functions with kubefn metadata
        for (const [name, handler] of Object.entries(mod)) {
          if (typeof handler === 'function' && handler._kubefn) {
            const meta = handler._kubefn;
            const fnInfo = {
              name,
              path: meta.path,
              methods: meta.methods || ['GET', 'POST'],
              group: groupName,
              handler,
            };

            functions.push(fnInfo);

            // Register route
            const routeKey = meta.path;
            this.routes.set(routeKey, fnInfo);

            for (const method of fnInfo.methods) {
              console.log(`  Registered route: ${method} ${meta.path} → ${groupName}.${name}`);
            }
          }
        }
      } catch (e) {
        console.error(`  Failed to load ${jsFile}: ${e.message}`);
      }
    }

    this.loadedGroups[groupName] = functions;
    return functions;
  }

  unloadGroup(groupName) {
    if (this.loadedGroups[groupName]) {
      for (const fn of this.loadedGroups[groupName]) {
        this.routes.delete(fn.path);
      }
      delete this.loadedGroups[groupName];
    }
  }

  resolve(method, requestPath) {
    // Exact match first
    const exact = this.routes.get(requestPath);
    if (exact && exact.methods.includes(method.toUpperCase())) {
      return { fn: exact, subPath: '' };
    }

    // Prefix match (longest wins)
    let bestMatch = null;
    let bestLen = 0;
    for (const [routePath, fn] of this.routes) {
      if (requestPath.startsWith(routePath) && routePath.length > bestLen) {
        if (fn.methods.includes(method.toUpperCase())) {
          bestMatch = fn;
          bestLen = routePath.length;
        }
      }
    }

    if (bestMatch) {
      return { fn: bestMatch, subPath: requestPath.slice(bestLen) };
    }
    return null;
  }

  allFunctions() {
    const fns = [];
    for (const [group, functions] of Object.entries(this.loadedGroups)) {
      for (const fn of functions) {
        for (const method of fn.methods) {
          fns.push({ method, path: fn.path, group, function: fn.name, runtime: 'node' });
        }
      }
    }
    return fns;
  }
}

module.exports = { FunctionLoader };
