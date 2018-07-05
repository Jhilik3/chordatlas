package org.twak.viewTrace.franken;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.apache.commons.io.FileUtils;
import org.twak.tweed.gen.skel.AppStore;
import org.twak.tweed.gen.skel.FacadeDesigner;
import org.twak.utils.collections.MultiMap;
import org.twak.utils.geom.DRectangle;
import org.twak.utils.ui.AutoDoubleSlider;
import org.twak.utils.ui.ListDownLayout;
import org.twak.utils.ui.auto.Auto;
import org.twak.viewTrace.facades.CGAMini;
import org.twak.viewTrace.facades.FRect;
import org.twak.viewTrace.facades.FeatureGenerator;
import org.twak.viewTrace.facades.GreebleSkel;
import org.twak.viewTrace.facades.MiniFacade;
import org.twak.viewTrace.facades.MiniFacade.Feature;
import org.twak.viewTrace.facades.Regularizer;
import org.twak.viewTrace.franken.Pix2Pix.Job;
import org.twak.viewTrace.franken.Pix2Pix.JobResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FacadeLabelApp extends App {

	public static final double FLOOR_HEIGHT = 2.5;
	public double regFrac = 0.1, regAlpha = 0.3, regScale = 0.4;
	
	public MiniFacade mf;
	public String texture;
	
	public FacadeLabelApp( MiniFacade mf, AppStore ass ) {
		super( );
		this.mf = mf;
	}

	public FacadeLabelApp( FacadeLabelApp o ) {
		super( o );
		this.mf = o.mf;
		this.regFrac   = o.regFrac;
		this.regAlpha  = o.regAlpha;
		this.regScale  = o.regScale;
		
		this.texture = o.texture;
	}

	@Override
	public App getUp(AppStore ac) {
		return ac.get(BuildingApp.class, mf.sf );
	}

	@Override
	public MultiMap<String, App> getDown(AppStore ac) {
		
		MultiMap<String, App> out = new MultiMap<>();
		
		out.put( "facade texture", ac.get(FacadeTexApp.class, mf ) );
		
		return out;
	}

	@Override
	public App copy() {
		return new FacadeLabelApp( this );
	}

	@Override
	public JComponent createUI( Runnable globalUpdate, SelectedApps apps ) {
		JPanel out = new JPanel(new ListDownLayout());
		
		
		if ( appMode == AppMode.Manual ) {

			JButton fac = new JButton( "edit facade" );
			fac.addActionListener( e -> new FacadeDesigner( apps.ass, mf, globalUpdate ) );
			out.add( fac );
			
			AutoDoubleSlider gfh = new AutoDoubleSlider( mf, "groundFloorHeight", "ground floor", 0, 5 ) {
				public void updated(double value) {
					FacadeDesigner.close();
					for (App a : apps) {
						((FacadeLabelApp)a).mf.groundFloorHeight = value;
						globalUpdate.run();
					}
				};
			};
			
		}
		else if (appMode == AppMode.Procedural ) {
			
			if ( ! ( mf.featureGen instanceof CGAMini ) )
				mf.featureGen = new CGAMini( mf );
			
			CGAMini cga = (CGAMini) mf.featureGen;
			
			out.add( new Auto( cga, true ) {
				
				public void updateOkayCancel() {
					super.updateOkayCancel();
					apply();
					globalUpdate.run();
				};
			} .build() ); 
			
			

		} else if ( appMode == AppMode.Net ) {

			out.add( new AutoDoubleSlider( this, "regFrac", "reg %", 0, 1 ) {
				public void updated( double value ) {

					for ( App a : apps )
						( (FacadeLabelApp) a ).regFrac = value;
					globalUpdate.run();
				};
			}.notWhileDragging() );

			out.add( new AutoDoubleSlider( this, "regAlpha", "reg alpha", 0, 1 ) {
				public void updated( double value ) {
					for ( App a : apps )
						( (FacadeLabelApp) a ).regAlpha = value;
					globalUpdate.run();
				};
			}.notWhileDragging() );

			out.add( new AutoDoubleSlider( this, "regScale", "reg scale", 0, 1 ) {
				public void updated( double value ) {
					for ( App a : apps )
						( (FacadeLabelApp) a ).regScale = value;
					globalUpdate.run();
				};
			}.notWhileDragging() );
		}
		
		return out;
	}
	
	@Override
	public void computeBatch(Runnable whenDone, List<App> batch, AppStore ass) {

		if ( appMode != AppMode.Net ) {
			for ( App a : batch ) {

				MiniFacade mf = ( (FacadeLabelApp) a ).mf;

				if ( appMode == AppMode.Manual ) {
					if ( !( mf.featureGen.getClass() == FeatureGenerator.class ) )
						mf.featureGen = new FeatureGenerator( mf.featureGen );
				} else if ( appMode == AppMode.Procedural )
					if ( !( mf.featureGen.getClass() == CGAMini.class ) ) {
						mf.featureGen = new CGAMini( mf );
					}
			}

			whenDone.run();
			return;
		}
		
		
		NetInfo ni = NetInfo.get(this);
		
		Pix2Pix p2 = new Pix2Pix( ni );
		
		BufferedImage bi = new BufferedImage( ni.resolution, ni.resolution, BufferedImage.TYPE_3BYTE_BGR );
		Graphics2D g = (Graphics2D) bi.getGraphics();

//		List<MiniFacade> mfb = batch.stream().map( x -> (MiniFacade)x.hasA ).collect( Collectors.toList() );

		for (App a : batch) {

			MiniFacade amf = ((FacadeLabelApp)a).mf;
			BuildingApp ba = ass.get (BuildingApp.class, amf.sf );
			
			DRectangle mini = Pix2Pix.findBounds( amf,  ba.createDormers, ass );

			if (mini.area() < 0.1)
				continue;

			amf.groundFloorHeight = 0;
			
			g.setColor( Color.black );
			g.fillRect( 0, 0, ni.resolution, ni.resolution );

			DRectangle mask = new DRectangle( mini );
			

			double scale = ni.resolution / Math.max( mini.height, mini.width );
			
			{
				mask = mask.scale( scale );
				mask.x = ( ni.resolution - mask.width ) * 0.5;
				mask.y = 0; 
			}

			Pix2Pix.drawFacadeBoundary( g, amf, mini, mask, ba.createDormers, ass );

			Meta meta = new Meta( amf, mask, mini );

			p2.addInput( bi, bi, null, meta, ass.get(FacadeLabelApp.class, amf).styleZ, FLOOR_HEIGHT * scale / 255.  );
		}

		g.dispose();
		
		p2.submit( new Job( new JobResult() {

			@Override
			public void finished( Map<Object, File> results ) {

				String dest;
				try {

					for ( Map.Entry<Object, File> e : results.entrySet() ) {

						Meta meta = (Meta)e.getKey();
						
						importLabels( ass, meta, new File (e.getValue().getParentFile(), e.getValue().getName()+"_boxes" ), ass );
						
						dest = Pix2Pix.importTexture( e.getValue(), -1, null, meta.mask, null, new BufferedImage[3] );

						if ( dest != null ) 
							ass.get (FacadeLabelApp.class, meta.mf ).texture = dest; // todo: set on FacadeTexApp after cropping for dormers
					}
					
					System.out.println("done here");
					
				} catch ( Throwable e ) {
					e.printStackTrace();
				}
				whenDone.run();
			}

		} ) );
	}

    private final static ObjectMapper om = new ObjectMapper();

    //{"other": [[196, 255, 0, 255], [0, 62, 0, 255]], 
    //"wall": [[62, 196, 0, 255]], 
    // "window": [[128, 192, 239, 255], [65, 114, 239, 255], [67, 113, 196, 217], [133, 191, 194, 217], [132, 185, 144, 161], [67, 107, 144, 161], [175, 183, 104, 118], [131, 171, 103, 120], [68, 105, 101, 119]]}
	private void importLabels( AppStore ac, Meta m,  File file, AppStore ass ) {
		
		if (file.exists()) {
			
			JsonNode root;
			try {
				
				m.mf.featureGen = new FeatureGenerator( m.mf );
				
				root = om.readTree( FileUtils.readFileToString( file ) );
				JsonNode node = root.get( "window" );
				
				FacadeTexApp fta = ass.get(FacadeTexApp.class, mf );
				
				i:
				for (int i = 0; i < node.size(); i++) {
					
					JsonNode rect = node.get( i );
					
					DRectangle f = new DRectangle( rect.get( 0 ).asDouble(), NetInfo.get(this).resolution - rect.get( 3 ).asDouble(),
							rect.get( 1 ).asDouble() - rect.get( 0 ).asDouble(),
							rect.get( 3 ).asDouble() - rect.get( 2 ).asDouble() );
							
					
					f = m.mfBounds.transform ( m.mask.normalize( f ) );
					
					{ // move away from edges
						double gap = 0.1;
						if (f.x < m.mfBounds.x + gap )
							f.x += gap;
						else
						if (f.x + f.width > m.mfBounds.getMaxX() - gap)
							f.x -= gap;
					}
					
//					if (m.mf.postState != null) {
//						for (Point2d p : f.points()) { 
//							if ( Loopz.inside( p, m.mf.postState.occluders) )
//								continue i;
//							if ( ! ( Loopz.inside( p, new LoopL<Point2d> ( (List) m.mf.postState.wallFaces) ) || 
//									 Loopz.inside( p, new LoopL<Point2d> ( (List) m.mf.postState.roofFaces) ) ) )
//								continue i;
//						}
//					}
					
					m.mf.featureGen.add( Feature.WINDOW, f );
				}
				
				if (regFrac > 0) {
					
					Regularizer reg = new Regularizer();
					
					reg.alpha = regAlpha;
					reg.scale = regScale;
					
					m.mf.featureGen = reg.go(Collections.singletonList( m.mf ), regFrac, null ).get( 0 ).featureGen;
					m.mf.featureGen.setMF(m.mf);
				}
				
				for (FRect window : m.mf.featureGen.getRects( Feature.WINDOW, Feature.SHOP )) {
					
					PanesLabelApp wla = ac.get ( PanesLabelApp.class , window );
					FacadeTexApp  mfa = ac.get ( FacadeTexApp.class  , m.mf);
					
						FRect nearestOld = closest( window, mfa.oldWindows );
						if ( nearestOld != null ) {
							PanesLabelApp opla = ac.get (PanesLabelApp.class, nearestOld );
							ac.set(PanesLabelApp.class, window, opla );
							PanesTexApp opta = ac.get (PanesTexApp.class, nearestOld );
							ac.set(PanesTexApp.class, window, opta );
							wla.styleZ = Arrays.copyOf ( opla.styleZ, opla.styleZ.length );
						}
				}
				
				System.out.println("done");
				
			} catch ( IOException e ) {
				e.printStackTrace();
			}
		}
	}

	private FRect closest( FRect window, ArrayList<FRect> oldWindows ) {
		
		double bestDist = Double.MAX_VALUE;
		FRect bestWin = null;
		if (oldWindows != null)
		for ( FRect r : oldWindows ) {
			double dist = window.getCenter().distanceSquared( r.getCenter() );

			if ( dist < bestDist ) {
				bestDist = dist;
				bestWin = r;
			}
		}
		
		return bestWin;
	}

	private static class Meta {
		DRectangle mask, mfBounds;
		MiniFacade mf;
		
		private Meta( MiniFacade mf, DRectangle mask, DRectangle mfBounds ) {
			this.mask = mask;
			this.mf = mf;
			this.mfBounds = mfBounds;
		}
	}
	
	public Enum[] getValidAppModes() {
		return new Enum[] {AppMode.Manual /* manual */, AppMode.Procedural, AppMode.Net};
	}
	
	@Override
	public void finishedBatches( List<App> all, AppStore ass ) {

		super.finishedBatches( all, ass );
		
		for (App a : all) {
			
			FacadeLabelApp fla = ( (FacadeLabelApp) a );
			MiniFacade mf = fla.mf;
			FacadeTexApp fta = ass.get( FacadeTexApp.class, ( (FacadeLabelApp) a ).mf );

			fta.oldWindows = new ArrayList<FRect>( mf.featureGen.getRects( Feature.WINDOW ) );
			fta.setChimneyTexture( ass, null );

			// compute dormer-roof locations
			new GreebleSkel( null, ass, mf.sf ).showSkeleton( mf.sf.skel.output, null, mf.sf.mr );
		}
	}
}
