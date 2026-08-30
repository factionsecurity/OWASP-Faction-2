import SunEditor from 'suneditor-react';
import 'suneditor/dist/css/suneditor.min.css';

declare global {
  interface Window {
    ice: {
      InlineChangeEditor: new (config: object) => {
        startTracking(): void;
        stopTracking(): void;
      };
    };
  }
}

interface Props {
  defaultValue: string;
  onChange: (html: string) => void;
  userId: string;
  userName: string;
}

export default function SunEditorICE({ defaultValue, onChange, userId, userName }: Props) {
  // onLoad receives (core, reload) from suneditor-react — core has context.element.wysiwyg
  const setupTracking = (core: any) => {
    if (typeof window.ice === 'undefined') {
      console.warn('SunEditorICE: window.ice not found — ice_patched.js may not have loaded');
      return;
    }

    try {
      const editableEl = core.context.element.wysiwyg;

      const tracker = new window.ice.InlineChangeEditor({
        element: editableEl,
        currentUser: { id: userId, name: userName },
        handleEvents: true,
        plugins: [
          'IceAddTitlePlugin',
          'IceEmdashPlugin',
          {
            name: 'IceCopyPastePlugin',
            settings: { preserve: 'p,a[href],span[id,class],em,strong' },
          },
        ],
      });
      tracker.startTracking();
    } catch (ex) {
      console.warn('ICE track-changes setup failed:', ex);
    }
  };

  return (
    <SunEditor
      onLoad={(core) => setupTracking(core)}
      defaultValue={defaultValue}
      onChange={onChange}
      autoFocus={false}
      height="auto"
      setOptions={{
        buttonList: [
          ['undo', 'redo'],
          ['bold', 'underline', 'italic', 'strike', 'removeFormat'],
          ['formatBlock', 'list'],
          ['link'],
        ],
        minHeight: '150px',
        height: 'auto',
      }}
    />
  );
}
