(function () {
    function closestTable(el) {
        return el.closest("table[data-jobbackup-table]");
    }

    function rowOfCheckbox(cb) {
        return cb.closest("tr");
    }

    function checkboxInRow(row) {
        return row.querySelector("input.jobbackup-row-check, input[type='checkbox']");
    }

    function setState(cb, checked, indeterminate) {
        cb.checked = !!checked;
        cb.indeterminate = !!indeterminate;
    }

    function childrenOf(row, table) {
        const id = row.dataset.id;
        if (!id) return [];
        return Array.from(table.querySelectorAll("tr[data-parent-id]"))
            .filter(r => r.dataset.parentId === id);
    }

    function parentOf(row, table) {
        const pid = row.dataset.parentId;
        if (!pid) return null;
        return table.querySelector(`tr[data-id="${cssEscape(pid)}"]`);
    }

    // Minimal CSS escaper for attribute selectors (good enough for job full names)
    function cssEscape(s) {
        return String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    }

    function cascadeDown(row, table, checked) {
        const cb = checkboxInRow(row);
        setState(cb, checked, false);

        childrenOf(row, table).forEach(child => cascadeDown(child, table, checked));
    }

    function recomputeFolderFromChildren(folderRow, table) {
        const folderCb = checkboxInRow(folderRow);
        const kids = childrenOf(folderRow, table);

        if (kids.length === 0) {
            // A folder with no children behaves like a leaf
            setState(folderCb, folderCb.checked, false);
            return;
        }

        const kidCbs = kids.map(checkboxInRow);

        const allChecked = kidCbs.every(c => c.checked && !c.indeterminate);
        const noneSelected = kidCbs.every(c => !c.checked && !c.indeterminate);

        if (allChecked) setState(folderCb, true, false);
        else if (noneSelected) setState(folderCb, false, false);
        else setState(folderCb, false, true);
    }

    function recomputeUpFrom(row, table) {
        let p = parentOf(row, table);
        while (p) {
            // Only folders should be tri-stated; if you mark jobs with data-type="job", keep this guard
            if (p.dataset.type === "folder") {
                recomputeFolderFromChildren(p, table);
            }
            p = parentOf(p, table);
        }
    }

    function recomputeHeaderSelectAll(table) {
        const header = table.querySelector("input.jobbackup-select-all");
        if (!header) return;

        const rows = Array.from(table.querySelectorAll("tbody tr"));
        const cbs = rows.map(checkboxInRow);

        const allChecked = cbs.length > 0 && cbs.every(c => c.checked && !c.indeterminate);
        const noneSelected = cbs.every(c => !c.checked && !c.indeterminate);

        if (allChecked) setState(header, true, false);
        else if (noneSelected) setState(header, false, false);
        else setState(header, false, true);
    }

    function toggle(cb) {
        const table = closestTable(cb);
        if (!table) return;

        const row = rowOfCheckbox(cb);
        const isFolder = row && row.dataset.type === "folder";

        // If a folder is toggled, cascade to descendants
        if (row && isFolder) {
            cascadeDown(row, table, cb.checked);
        } else {
            // Ensure leaf never stays indeterminate
            cb.indeterminate = false;
        }

        // Recompute ancestors (folder parents can have ancestors too)
        if (row) {
            recomputeUpFrom(row, table);
        }

        // Update header select-all state
        recomputeHeaderSelectAll(table);
    }

    function selectAllFromHeader(headerCb) {
        const table = closestTable(headerCb);
        if (!table) return;

        const checked = headerCb.checked;

        // Apply to all rows
        table.querySelectorAll("tbody input[type='checkbox']").forEach(cb => {
            setState(cb, checked, false);
        });

        // Recompute folders bottom-up:
        // simplest: recomputeUp from every leaf; acceptable for moderate lists.
        const rows = Array.from(table.querySelectorAll("tbody tr"));
        rows.forEach(r => recomputeUpFrom(r, table));

        recomputeHeaderSelectAll(table);
    }

    window.jobBackup = {
        toggle,
        selectAllFromHeader,
    };
})();
