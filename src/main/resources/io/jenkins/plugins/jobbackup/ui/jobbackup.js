(function () {
    "use strict";

    // ---------- DOM helpers ----------

    function closestTable(el) {
        return el && el.closest ? el.closest("table[data-jobbackup-table]") : null;
    }

    function rowOfCheckbox(cb) {
        return cb && cb.closest ? cb.closest("tr") : null;
    }

    function checkboxInRow(row) {
        if (!row) return null;
        // Prefer explicit row checkbox class if you have it, fallback to any checkbox in the row
        return row.querySelector("input.jobbackup-row-check") || row.querySelector("input[type='checkbox']");
    }

    function setState(cb, checked, indeterminate) {
        if (!cb) return;
        cb.checked = !!checked;
        cb.indeterminate = !!indeterminate;
    }

    function escapeAttrValue(v) {
        // Prefer native CSS.escape when available
        if (typeof CSS !== "undefined" && CSS.escape) return CSS.escape(String(v));
        // Minimal safe fallback for attribute selectors
        return String(v).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    }

    function headerSelectAllCheckbox(table) {
        if (!table) return null;

        // Works with:
        // 1) <input class="jobbackup-select-all">
        // 2) Jenkins <f:checkbox name="_selectAll"> rendered in thead
        // 3) Anywhere inside table header, as long as name matches
        return (
            table.querySelector("input.jobbackup-select-all[type='checkbox']") ||
            table.querySelector("thead input[type='checkbox'][name='_selectAll']") ||
            table.querySelector("input[type='checkbox'][name='_selectAll']")
        );
    }

    // ---------- Tree helpers (folders/jobs) ----------

    function childrenOf(row, table) {
        const id = row && row.dataset ? row.dataset.id : null;
        if (!id) return [];
        return Array.from(table.querySelectorAll("tbody tr[data-parent-id]"))
            .filter(r => r.dataset.parentId === id);
    }

    function parentOf(row, table) {
        const pid = row && row.dataset ? row.dataset.parentId : null;
        if (!pid) return null;
        return table.querySelector(`tbody tr[data-id="${escapeAttrValue(pid)}"]`);
    }

    function cascadeDown(row, table, checked) {
        const cb = checkboxInRow(row);
        setState(cb, checked, false);

        childrenOf(row, table).forEach(child => cascadeDown(child, table, checked));
    }

    function recomputeFolderFromChildren(folderRow, table) {
        const folderCb = checkboxInRow(folderRow);
        const kids = childrenOf(folderRow, table);

        if (!folderCb) return;

        if (kids.length === 0) {
            // A folder without children behaves like a leaf
            setState(folderCb, folderCb.checked, false);
            return;
        }

        const kidCbs = kids.map(checkboxInRow).filter(Boolean);

        const allChecked = kidCbs.length > 0 && kidCbs.every(c => c.checked && !c.indeterminate);
        const noneSelected = kidCbs.every(c => !c.checked && !c.indeterminate);

        if (allChecked) setState(folderCb, true, false);
        else if (noneSelected) setState(folderCb, false, false);
        else setState(folderCb, false, true);
    }

    function recomputeUpFrom(row, table) {
        let p = parentOf(row, table);
        while (p) {
            if (p.dataset && p.dataset.type === "folder") {
                recomputeFolderFromChildren(p, table);
            }
            p = parentOf(p, table);
        }
    }

    function recomputeHeaderSelectAll(table) {
        const header = headerSelectAllCheckbox(table);
        if (!header) return;

        const rows = Array.from(table.querySelectorAll("tbody tr"));
        const cbs = rows.map(checkboxInRow).filter(Boolean);

        const allChecked = cbs.length > 0 && cbs.every(c => c.checked && !c.indeterminate);
        const noneSelected = cbs.length === 0 || cbs.every(c => !c.checked && !c.indeterminate);

        if (allChecked) setState(header, true, false);
        else if (noneSelected) setState(header, false, false);
        else setState(header, false, true);
    }

    // ---------- CSP-safe behavior (no inline onclick) ----------

    function toggle(cb) {
        if (!cb || cb.type !== "checkbox") return;

        const table = closestTable(cb);
        if (!table) return;

        const row = rowOfCheckbox(cb);
        const isFolder = row && row.dataset && row.dataset.type === "folder";

        // Folder toggles cascade to descendants
        if (row && isFolder) {
            cascadeDown(row, table, cb.checked);
        } else {
            // Leaves should never remain indeterminate
            cb.indeterminate = false;
        }

        // Recompute ancestors
        if (row) {
            recomputeUpFrom(row, table);
        }

        // Recompute header state
        recomputeHeaderSelectAll(table);
    }

    function selectAllFromHeader(headerCb) {
        if (!headerCb || headerCb.type !== "checkbox") return;

        const table = closestTable(headerCb);
        if (!table) return;

        const checked = headerCb.checked;

        // Apply only to body checkboxes (avoid touching header)
        table.querySelectorAll("tbody input[type='checkbox']").forEach(cb => {
            setState(cb, checked, false);
        });

        // Recompute folder states upward (safe and simple)
        const rows = Array.from(table.querySelectorAll("tbody tr"));
        rows.forEach(r => recomputeUpFrom(r, table));

        // Ensure header is correct (checked/unchecked/indeterminate)
        recomputeHeaderSelectAll(table);
    }

    function applyIndentation(table) {
        if (!table) return;

        table.querySelectorAll(".jobbackup-item[data-jobbackup-depth]").forEach(function (el) {
            let depth = parseInt(el.getAttribute("data-jobbackup-depth") || "0", 10);
            if (Number.isNaN(depth) || depth < 0) depth = 0;
            el.style.paddingLeft = String(depth * 16) + "px";
        });
    }

    function bindTable(table) {
        if (!table || table.dataset.jobbackupBound === "true") return;
        table.dataset.jobbackupBound = "true";

        // Header select-all
        const header = headerSelectAllCheckbox(table);
        if (header) {
            header.addEventListener("change", function () {
                selectAllFromHeader(header);
            });
        }

        // Row checkboxes
        table.querySelectorAll("tbody input.jobbackup-row-check[type='checkbox']").forEach(function (cb) {
            cb.addEventListener("change", function () {
                toggle(cb);
            });
        });

        applyIndentation(table);
        recomputeHeaderSelectAll(table);
    }

    function init() {
        document.querySelectorAll("table[data-jobbackup-table]").forEach(function (table) {
            bindTable(table);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }

    // Backwards compatibility: if any old Jelly still calls window.jobBackup.* it will keep working.
    window.jobBackup = {
        toggle,
        selectAllFromHeader,
        recomputeHeaderSelectAll
    };
})();
