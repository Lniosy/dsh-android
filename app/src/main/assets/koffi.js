const SIZES = {
  DSH_STARTUPINFOW: 104,
  DSH_PROCESS_INFORMATION: 24,
  PROCESSENTRY32W: 568,
  FILETIME: 8,
}

function type(name, size = 8) {
  return { name: String(name), size, alignment: 8, members: {}, primitive: "Record" }
}

function load() {
  return {
    func() {
      return () => {
        throw new Error("koffi stub: Android 无原生 FFI")
      }
    },
  }
}

const koffi = {
  load,
  pointer: (inner) => type(`ptr<${inner}>`, 8),
  struct: (name) => type(name, SIZES[name] ?? 8),
  array: (inner, count) => type(`arr<${inner}>`, Number(count) || 0),
  alloc: () => ({}),
  encode() {},
  decode() {
    return {}
  },
  errno: () => 0,
  sizeof: (spec) => (typeof spec === "object" && spec?.size) || 8,
  alignof: () => 8,
  offsetof: () => 0,
  type,
}

export default koffi
export const {
  load: loadLib,
  pointer,
  struct,
  array,
  alloc,
  encode,
  decode,
  errno,
} = koffi
