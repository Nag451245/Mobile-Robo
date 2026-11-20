# Lists desktop icon names and screen rectangles using the Windows accessibility API (MSAA).
# Read-only: no memory access into Explorer, no hooks. Output: one "name<TAB>x<TAB>y<TAB>w<TAB>h" line per icon.
$src = @"
using System; using System.Text; using System.Runtime.InteropServices; using Accessibility;
public static class BlinkDeskIcons {
  [DllImport("oleacc.dll")] static extern int AccessibleObjectFromWindow(IntPtr hwnd, uint id, ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object acc);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern IntPtr FindWindow(string c, string n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern IntPtr FindWindowEx(IntPtr p, IntPtr a, string c, string n);
  static IntPtr ListView() {
    IntPtr dv = FindWindowEx(FindWindow("Progman", null), IntPtr.Zero, "SHELLDLL_DefView", null);
    IntPtr w = IntPtr.Zero;
    while (dv == IntPtr.Zero && (w = FindWindowEx(IntPtr.Zero, w, "WorkerW", null)) != IntPtr.Zero) dv = FindWindowEx(w, IntPtr.Zero, "SHELLDLL_DefView", null);
    return dv == IntPtr.Zero ? IntPtr.Zero : FindWindowEx(dv, IntPtr.Zero, "SysListView32", null);
  }
  public static string Get() {
    IntPtr lv = ListView(); if (lv == IntPtr.Zero) return "";
    Guid g = new Guid("618736E0-3C3D-11CF-810C-00AA00389B71"); object o;
    if (AccessibleObjectFromWindow(lv, 0xFFFFFFFC, ref g, out o) != 0 || o == null) return "";
    IAccessible a = (IAccessible)o; var sb = new StringBuilder(); int n = a.accChildCount;
    for (int i = 1; i <= n && i <= 300; i++) {
      try { int l, t, w, h; a.accLocation(out l, out t, out w, out h, i); string name = (a.get_accName(i) ?? "").Replace("\t", " ").Replace("\n", " ").Replace("\r", " ");
        if (w > 0 && h > 0) sb.Append(name).Append('\t').Append(l).Append('\t').Append(t).Append('\t').Append(w).Append('\t').Append(h).Append('\n'); } catch {}
    }
    return sb.ToString();
  }
}
"@
Add-Type -TypeDefinition $src -ReferencedAssemblies Accessibility
[Console]::Out.Write([BlinkDeskIcons]::Get())
