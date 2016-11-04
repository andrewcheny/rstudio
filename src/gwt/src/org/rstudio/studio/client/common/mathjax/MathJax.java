/*
 * MathJax.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.common.mathjax;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.rstudio.core.client.BrowseCap;
import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.MapUtil;
import org.rstudio.core.client.MapUtil.ForEachCommand;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.container.SafeMap;
import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.layout.FadeOutAnimation;
import org.rstudio.core.client.regex.Pattern;
import org.rstudio.studio.client.common.mathjax.display.MathJaxPopupPanel;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOptions;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefsAccessor;
import org.rstudio.studio.client.workbench.views.source.editors.text.ChunkOutputSize;
import org.rstudio.studio.client.workbench.views.source.editors.text.ChunkOutputWidget;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay;
import org.rstudio.studio.client.workbench.views.source.editors.text.PinnedLineWidget;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay.AnchoredSelection;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.LineWidget;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Range;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Token;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.TokenIterator;
import org.rstudio.studio.client.workbench.views.source.editors.text.events.CursorChangedEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.events.CursorChangedHandler;
import org.rstudio.studio.client.workbench.views.source.editors.text.events.DocumentChangedEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.ChunkOutputHost;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.TextEditingTargetNotebook;
import org.rstudio.studio.client.workbench.views.source.model.DocUpdateSentinel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.dom.client.Style.Visibility;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.BlurHandler;
import com.google.gwt.event.logical.shared.AttachEvent;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.FlowPanel;

public class MathJax
{
   interface MathJaxTypesetCallback
   {
      void onMathJaxTypesetComplete(boolean error);
   }
   
   public MathJax(DocDisplay docDisplay, DocUpdateSentinel sentinel,
         UIPrefs prefs)
   {
      docDisplay_ = docDisplay;
      sentinel_ = sentinel;
      prefs_ = prefs;
      popup_ = new MathJaxPopupPanel(this);
      renderQueue_ = new MathJaxRenderQueue(this);
      handlers_ = new ArrayList<HandlerRegistration>();
      cowToPlwMap_ = new SafeMap<ChunkOutputWidget, PinnedLineWidget>();
      lwToPlwMap_ = new SafeMap<LineWidget, ChunkOutputWidget>();
      pendingLineWidgets_ = new HashSet<Integer>();

      handlers_.add(popup_.addAttachHandler(new AttachEvent.Handler()
      {
         @Override
         public void onAttachOrDetach(AttachEvent event)
         {
            if (event.isAttached())
               beginCursorMonitoring();
            else
               endCursorMonitoring();
         }
      }));
      
      handlers_.add(docDisplay_.addBlurHandler(new BlurHandler()
      {
         @Override
         public void onBlur(BlurEvent event)
         {
            endRender();
         }
      }));
      
      handlers_.add(docDisplay_.addAttachHandler(new AttachEvent.Handler()
      {
         @Override
         public void onAttachOrDetach(AttachEvent event)
         {
            if (!event.isAttached())
            {
               detachHandlers();
               return;
            }
         }
      }));
      
      handlers_.add(docDisplay_.addDocumentChangedHandler(new DocumentChangedEvent.Handler()
      {
         Timer bgRenderTimer_ = new Timer()
         {
            @Override
            public void run()
            {
               // re-render latex in a visible mathjax popup
               if (popup_.isShowing() && anchor_ != null)
               {
                  renderLatex(anchor_.getRange(), false);
                  return;
               }
               
               // re-render latex in a line widget if we already have one
               Token token = docDisplay_.getTokenAt(docDisplay_.getCursorPosition());
               if (token != null && token.hasType("latex"))
               {
                  Range range = MathJaxUtil.getLatexRange(docDisplay_);
                  if (range != null)
                  {
                     int endRow = range.getEnd().getRow();
                     LineWidget lineWidget = docDisplay_.getLineWidgetForRow(endRow);
                     if (lineWidget != null)
                        renderLatex(range, false);
                  }
               }
            }
         };
         
         @Override
         public void onDocumentChanged(DocumentChangedEvent event)
         {
            bgRenderTimer_.schedule(200);
            Scheduler.get().scheduleDeferred(new ScheduledCommand()
            {
               @Override
               public void execute()
               {
                  MapUtil.forEach(cowToPlwMap_, new ForEachCommand<ChunkOutputWidget, PinnedLineWidget>()
                  {
                     @Override
                     public void execute(ChunkOutputWidget cow, PinnedLineWidget plw)
                     {
                        // for single-line chunks, e.g. with '$$ ... $$', detect
                        // if the boundaries have been mutated
                        int row = plw.getRow();
                        String line = docDisplay_.getLine(row);
                        Pattern reDoubleDollarStart = Pattern.create("^\\s*\\$\\$");
                        Pattern reDoubleDollarEnd   = Pattern.create("\\$\\$\\s*$");
                        
                        boolean isMathJax =
                              reDoubleDollarStart.test(line) &&
                              reDoubleDollarEnd.test(line);
                        
                        if (!isMathJax)
                        {
                           removeChunkOutputWidget(cow);
                           return;
                        }
                        
                        // for mathjax 'chunks', detect whether the start of the
                        // chunk has been mutated / destroyed
                        Pattern reDoubleDollar = Pattern.create("^\\s*\\$\\$\\s*$");
                        if (reDoubleDollar.test(line))
                        {
                           TokenIterator it = docDisplay_.createTokenIterator();
                           Token token = it.moveToPosition(row - 1, 0);
                           if (token != null && !token.hasType("latex"))
                           {
                              removeChunkOutputWidget(cow);
                              return;
                           }
                        }
                     }
                  });
               }
            });
         }
      }));
      
   }
   
   public void renderLatex()
   {
      final List<Range> ranges = MathJaxUtil.findLatexChunks(docDisplay_);
      renderQueue_.enqueueAndRender(ranges);
   }
   
   public void renderLatex(Range range)
   {
      renderLatex(range, false);
   }
   
   public void renderLatex(Range range, boolean background)
   {
      renderLatex(range, background,  null);
   }
   
   public void renderLatex(final Range range,
                           final boolean background,
                           final MathJaxTypesetCallback callback)
   {
      MathJaxLoader.withMathJaxLoaded(new MathJaxLoader.Callback()
      {
         @Override
         public void onLoaded(boolean alreadyLoaded)
         {
            renderLatexImpl(range, background, callback);
         }
      });
   }
   
   public void promotePopupToLineWidget()
   {
      if (range_ == null)
         return;
      
      renderLatex(range_, false);
   }
   
   // Private Methods ----
   
   private void renderLatexImpl(final Range range,
                                final boolean background,
                                final MathJaxTypesetCallback callback)
   {
      String text = docDisplay_.getTextForRange(range);
      
      // render latex chunks as line widgets unless document or global
      // preferences indicate otherwise
      if (sentinel_.getBoolProperty(
            TextEditingTargetNotebook.CONTENT_PREVIEW_INLINE, 
            prefs_.showLatexPreviewOnCursorIdle().getValue() == 
               UIPrefsAccessor.LATEX_PREVIEW_SHOW_ALWAYS))
      {
         boolean isLatexChunk = text.startsWith("$$") && text.endsWith("$$");
         if (isLatexChunk)
         {
            // don't render if chunk contents empty
            if (isEmptyLatexChunk(text))
               return;
            
            // don't render if this is a background render request and
            // the line widget is collapsed
            final int row = range.getEnd().getRow();
            if (isLineWidgetCollapsed(row))
               return;
            
            renderLatexLineWidget(range, text, callback);
            return;
         }
      }
      
      // if the popup is already showing, just re-render within that popup
      // (don't reset render state)
      if (popup_.isShowing())
      {
         renderPopup(text, callback);
         return;
      }
      
      resetRenderState();
      range_ = range;
      anchor_ = docDisplay_.createAnchoredSelection(range.getStart(), range.getEnd());
      lastRenderedText_ = "";
      renderPopup(text, callback);
   }
   
   private void renderLatexLineWidget(final Range range,
                                      final String text,
                                      final MathJaxTypesetCallback callback)
   {
      // end a previous render session if necessary (e.g. if a popup is showing)
      endRender();
      
      final int row = range.getEnd().getRow();
      LineWidget widget = docDisplay_.getLineWidgetForRow(row);
      
      if (widget == null)
      {
         // if someone is already attempting to generate a line widget for this row, bail
         if (pendingLineWidgets_.contains(row))
            return;
         
         // mark this row as waiting for a line widget
         pendingLineWidgets_.add(row);
         
         // if we don't have a widget, create one and render the LaTeX once
         // the widget is attached to the editor
         createMathJaxLineWidget(row, 
               // render bare output if start and end are on the same line
               range.getStart().getRow() == range.getEnd().getRow(), 
               new CommandWithArg<LineWidget>()
         {
            @Override
            public void execute(LineWidget w)
            {
               pendingLineWidgets_.remove(row);
               renderLatexLineWidget(w, range, text, callback);
            }
         });
      }
      else
      {
         pendingLineWidgets_.remove(row);
         renderLatexLineWidget(widget, range, text, callback);
      }
   }

   private void renderLatexLineWidget(LineWidget widget,
                                     final Range range,
                                     final String text,
                                     final MathJaxTypesetCallback callback)
   {
      final Element el = DomUtils.getFirstElementWithClassName(
            widget.getElement(),
            MATHJAX_ROOT_CLASSNAME);
      
      // call 'onLineWidgetChanged' to ensure the widget is attached
      docDisplay_.onLineWidgetChanged(widget);
      
      // defer typesetting just to ensure that the widget has actually been
      // attached to the DOM
      final LineWidget lineWidget = widget;
      Scheduler.get().scheduleDeferred(new ScheduledCommand()
      {
         @Override
         public void execute()
         {
            mathjaxTypeset(el, text, new MathJaxTypesetCallback()
            {
               @Override
               public void onMathJaxTypesetComplete(boolean error)
               {
                  // re-position the element
                  int height = el.getOffsetHeight() + 30;
                  Element ppElement = el.getParentElement().getParentElement();
                  ppElement.getStyle().setHeight(height, Unit.PX);
                  docDisplay_.onLineWidgetChanged(lineWidget);
                  
                  // invoke supplied callback
                  if (callback != null)
                     callback.onMathJaxTypesetComplete(error);
               }
            });
         }
      });
   }
   
   private boolean isLineWidgetCollapsed(int row)
   {
      LineWidget widget = docDisplay_.getLineWidgetForRow(row);
      if (widget == null)
         return false;
      
      ChunkOutputWidget cow = lwToPlwMap_.get(widget);
      if (cow == null)
         return false;
      
      return cow.getExpansionState() == ChunkOutputWidget.COLLAPSED;
   }
   
   private void removeChunkOutputWidget(final ChunkOutputWidget widget)
   {
      final PinnedLineWidget plw = cowToPlwMap_.get(widget);
      if (plw == null)
         return;

      FadeOutAnimation anim = new FadeOutAnimation(widget, new Command()
      {
         @Override
         public void execute()
         {
            cowToPlwMap_.remove(widget);
            lwToPlwMap_.remove(plw.getLineWidget());
            plw.detach();
         }
      });
      
      anim.run(400);
   }
   
   private FlowPanel createMathJaxContainerWidget()
   {
      final FlowPanel panel = new FlowPanel();
      panel.addStyleName(MATHJAX_ROOT_CLASSNAME);
      panel.addStyleName(RES.styles().mathjaxRoot());
      if (BrowseCap.isWindowsDesktop())
         panel.addStyleName(RES.styles().mathjaxRootWindows());
      return panel;
   }
   
   private void createMathJaxLineWidget(int row, boolean bare,
         final CommandWithArg<LineWidget> onAttached)
   {
      final FlowPanel panel = createMathJaxContainerWidget();
      
      ChunkOutputHost host = new ChunkOutputHost()
      {
         private int lastHeight_ = Integer.MAX_VALUE;
         
         @Override
         public void onOutputRemoved(final ChunkOutputWidget widget)
         {
            removeChunkOutputWidget(widget);
         }
         
         @Override
         public void onOutputHeightChanged(ChunkOutputWidget widget,
                                           int height,
                                           boolean ensureVisible)
         {
            final PinnedLineWidget plw = cowToPlwMap_.get(widget);
            if (plw == null)
               return;
            
            // munge the size of the frame, to avoid issues where the
            // frame's size changes following a collapse + expand
            boolean isExpansion = lastHeight_ <= height;
            if (isExpansion)
               widget.getFrame().setHeight((height + 4) + "px");
            lastHeight_ = height;
            
            // update the height and report to doc display
            LineWidget lineWidget = plw.getLineWidget();
            lineWidget.setPixelHeight(height);
            docDisplay_.onLineWidgetChanged(lineWidget);
         }
      };
      
      final ChunkOutputWidget outputWidget = new ChunkOutputWidget(
            StringUtil.makeRandomId(8),
            StringUtil.makeRandomId(8),
            RmdChunkOptions.create(),
            ChunkOutputWidget.EXPANDED,
            false, // can close
            host,
            bare ? ChunkOutputSize.Bare : ChunkOutputSize.Default);
      
      outputWidget.setRootWidget(panel);
      outputWidget.hideSatellitePopup();

      PinnedLineWidget plWidget = new PinnedLineWidget(
            LINE_WIDGET_TYPE,
            docDisplay_,
            outputWidget,
            row,
            null,
            new PinnedLineWidget.Host()
            {
               @Override
               public void onLineWidgetRemoved(LineWidget widget)
               {
                  // no action necessary here; this is taken care of by the
                  // hosting chunk (see onOutputRemoved)
               }
               
               @Override
               public void onLineWidgetAdded(LineWidget widget)
               {
                  onAttached.execute(widget);
               }
            });

      cowToPlwMap_.put(outputWidget, plWidget);
      lwToPlwMap_.put(plWidget.getLineWidget(), outputWidget);
   }
   
   private void resetRenderState()
   {
      if (anchor_ != null)
      {
         anchor_.detach();
         anchor_ = null;
      }
      
      if (cursorChangedHandler_ != null)
      {
         cursorChangedHandler_.removeHandler();
         cursorChangedHandler_ = null;
      }
      
      range_ = null;
      lastRenderedText_ = "";
   }
   
   private void renderPopup(final String text,
                            final MathJaxTypesetCallback callback)
   {
      // no need to re-render if text hasn't changed or is empty
      if (text.equals(lastRenderedText_))
         return;
      
      // if empty, hide popup
      if (text.isEmpty())
      {
         endRender();
         return;
      }
      
      // no need to re-position popup if already showing;
      // just typeset
      if (popup_.isShowing())
      {
         mathjaxTypeset(popup_.getContentElement(), text, callback);
         return;
      }
      
      // attach popup to DOM but render offscreen
      popup_.setPopupPosition(100000, 100000);
      popup_.show();
      
      // typeset and position after typesetting finished
      mathjaxTypeset(popup_.getContentElement(), text, new MathJaxTypesetCallback()
      {
         
         @Override
         public void onMathJaxTypesetComplete(boolean error)
         {
            // re-position popup after render
            popup_.positionNearRange(docDisplay_, range_);
            popup_.show();
            
            // invoke user callback if provided
            if (callback != null)
               callback.onMathJaxTypesetComplete(error);
         }
      });
   }
   
   private void endRender()
   {
      resetRenderState();
      popup_.hide();
   }
   
   private void onMathJaxTypesetCompleted(final Object mathjaxElObject,
                                          final String text,
                                          final boolean error,
                                          final Object commandObject,
                                          final int attempt)
   {
      final Element mathjaxEl = (Element) mathjaxElObject;

      if (attempt < MAX_RENDER_ATTEMPTS)
      {
         // if mathjax displayed an error, try re-rendering once more
         Element[] errorEls = DomUtils.getElementsByClassName(mathjaxEl, 
               "MathJax_Error");
         if (errorEls != null && errorEls.length > 0 &&
             attempt < MAX_RENDER_ATTEMPTS)
         {
            // hide the error and retry in 25ms (experimentally this seems to
            // produce a better shot at rendering successfully than an immediate
            // or deferred retry)
            mathjaxEl.getStyle().setVisibility(Visibility.HIDDEN);
            new Timer()
            {
               @Override
               public void run()
               {
                  mathjaxTypeset(mathjaxEl, text, commandObject, attempt + 1);
               }
            }.schedule(25);
            return;
         }
      }
      
      // show whatever we've got (could be an error if we ran out of retries)
      mathjaxEl.getStyle().setVisibility(Visibility.VISIBLE);
      
      // execute callback
      if (commandObject != null && commandObject instanceof MathJaxTypesetCallback)
      {
         MathJaxTypesetCallback callback = (MathJaxTypesetCallback) commandObject;
         callback.onMathJaxTypesetComplete(error);
      }
      
      if (!error)
      {
         lastRenderedText_ = text;
      }
   }
   
   private final void mathjaxTypeset(Element el, String currentText)
   {
      mathjaxTypeset(el, currentText, null);
   }

   private final void mathjaxTypeset(Element el, String currentText, 
         Object command)
   {
      mathjaxTypeset(el, currentText, command, 0);
   }
   
   private final native void mathjaxTypeset(Element el,
                                            String currentText,
                                            Object command,
                                            int attempt)
   /*-{
      var MathJax = $wnd.MathJax;
      
      // save last rendered text
      var jax = MathJax.Hub.getAllJax(el)[0];
      var lastRenderedText = jax && jax.originalText || "";
      
      // update text in element
      el.innerText = currentText;
      
      // typeset element
      var self = this;
      MathJax.Hub.Queue($entry(function() {
         MathJax.Hub.Typeset(el, $entry(function() {
            // restore original typesetting on failure
            jax = MathJax.Hub.getAllJax(el)[0];
            var error = !!(jax && jax.texError);
            if (error && lastRenderedText.length)
               jax.Text(lastRenderedText);

            // callback to GWT
            self.@org.rstudio.studio.client.common.mathjax.MathJax::onMathJaxTypesetCompleted(Ljava/lang/Object;Ljava/lang/String;ZLjava/lang/Object;I)(el, currentText, error, command, attempt);
         }));
      }));
   }-*/;
   
   private void beginCursorMonitoring()
   {
      endCursorMonitoring();
      cursorChangedHandler_ = docDisplay_.addCursorChangedHandler(new CursorChangedHandler()
      {
         @Override
         public void onCursorChanged(CursorChangedEvent event)
         {
            Position position = event.getPosition();
            if (anchor_ == null || !anchor_.getRange().contains(position))
               endRender();
         }
      });
   }
   
   private void endCursorMonitoring()
   {
      if (cursorChangedHandler_ != null)
      {
         cursorChangedHandler_.removeHandler();
         cursorChangedHandler_ = null;
      }
   }
   
   private boolean isEmptyLatexChunk(String text)
   {
      return text.matches("^\\$*\\s*\\$*$");
   }
   
   private void detachHandlers()
   {
      for (HandlerRegistration handler : handlers_)
         handler.removeHandler();
      handlers_.clear();
   }
   
   public interface Styles extends CssResource
   {
      String mathjaxRoot();
      String mathjaxRootWindows();
   }

   public interface Resources extends ClientBundle
   {
      @Source("MathJax.css")
      Styles styles();
   }

   private static Resources RES = GWT.create(Resources.class);
   static
   {
      RES.styles().ensureInjected();
   }
   
   private final DocDisplay docDisplay_;
   private final DocUpdateSentinel sentinel_;
   private final UIPrefs prefs_;
   private final MathJaxPopupPanel popup_;
   private final MathJaxRenderQueue renderQueue_;
   private final List<HandlerRegistration> handlers_;
   private final SafeMap<ChunkOutputWidget, PinnedLineWidget> cowToPlwMap_;
   private final SafeMap<LineWidget, ChunkOutputWidget> lwToPlwMap_;
   private final Set<Integer> pendingLineWidgets_;
   
   private AnchoredSelection anchor_;
   private Range range_;
   private HandlerRegistration cursorChangedHandler_;
   private String lastRenderedText_ = "";
   
   // sometimes MathJax fails to render initially but succeeds if asked to do so
   // again; this is the maximum number of times we'll try to re-render the same
   // text automatically before giving up
   private static final int MAX_RENDER_ATTEMPTS = 2;
   
   public static final String LINE_WIDGET_TYPE = "mathjax-preview";
   public static final String MATHJAX_ROOT_CLASSNAME = "rstudio-mathjax-root";
}
